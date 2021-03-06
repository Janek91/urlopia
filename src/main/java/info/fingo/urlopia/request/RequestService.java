package info.fingo.urlopia.request;

import info.fingo.urlopia.events.OccasionalInfoEvent;
import info.fingo.urlopia.events.OccasionalResponseEvent;
import info.fingo.urlopia.events.RequestAcceptedEvent;
import info.fingo.urlopia.history.DurationCalculator;
import info.fingo.urlopia.history.HistoryService;
import info.fingo.urlopia.holidays.HolidayService;
import info.fingo.urlopia.user.User;
import info.fingo.urlopia.user.UserDTO;
import info.fingo.urlopia.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Tomasz Urbas
 */
@Service
@Transactional
public class RequestService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RequestRepository requestRepository;

    @Autowired
    private RequestFactory requestFactory;

    @Autowired
    private AcceptanceService acceptanceService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AcceptanceRepository acceptanceRepository;

    @Autowired
    private HolidayService holidayService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    public RequestDTO getRequest(long id) {
        Request request = requestRepository.findOne(id);
        return requestFactory.create(request);
    }

    public List<RequestDTO> getRequestsFromWorker(long userId) {
        List<Request> requests = requestRepository.findByRequesterId(userId);

        return requests.stream()
                .map(requestFactory::create)
                .collect(Collectors.toList());
    }

    public List<RequestDTO> getRequestsFromAdmin() {
        List<Request> requests = requestRepository.findAll();

        return requests.stream()
                .map(requestFactory::create)
                .collect(Collectors.toList());
    }

    public List<RequestDTO> getRequestsFromWorker(long userId, LocalDateTime lastUpdate) {
        int newsCount = requestRepository.countByRequesterIdAndModifiedAfter(userId, lastUpdate);

        List<RequestDTO> requests = new LinkedList<>();

        // if there is any new request
        if (newsCount > 0) {
            requests = getRequestsFromWorker(userId);
        }

        return requests;
    }

    public List<RequestDTO> getRequestsFromAdmin(LocalDateTime lastUpdate) {
        int newsCount = requestRepository.countByModifiedAfter(lastUpdate);

        List<RequestDTO> requests = new LinkedList<>();

        // if there is any new request
        if (newsCount > 0) {
            requests = getRequestsFromAdmin();
        }

        return requests;
    }

    /*
     *  Check if one request overlaps another
     */
    private boolean isOverlapped(RequestDTO new_request, RequestDTO request) {
        if(new_request.getType() == Request.Type.NORMAL) {    // if it's not occasional request (we don't want to stress these people)
            return !(request.getEndDate().isBefore(new_request.getStartDate()))
                    && !(new_request.getEndDate().isBefore(request.getStartDate()));
        }

        return false;
    }

    /*
     *  Check if request is overlapped by another request from the same worker
     */
    private boolean isRequestOverlapped(RequestDTO new_request) {
        List<RequestDTO> workerRequests = this.getRequestsFromWorker(new_request.getRequester().getId()).stream()
                .filter(request -> request.getStatus() == Request.Status.ACCEPTED
                                || request.getStatus() == Request.Status.PENDING)
                .collect(Collectors.toList());

        for (RequestDTO workerRequest : workerRequests) {
            if (isOverlapped(new_request, workerRequest)) {
                return true;
            }
        }

        return false;
    }

    /*
     *  Insert new request to database
     */
    private Request insert(UserDTO requester, LocalDate startDate, LocalDate endDate, Request.Type type, Request.TypeInfo typeInfo, Request.Status status)
            throws RequestOverlappingException {
        LocalDate admissibleStart = LocalDate.now().minus(Period.ofMonths(1));

        // start date must be before or equals end date
        if (startDate.isAfter(admissibleStart) && !endDate.isBefore(startDate)) {
            User requesterDB = userRepository.findOne(requester.getId());

            Request request = new Request(requesterDB, startDate, endDate, type, typeInfo, status);
            RequestDTO requestDTO = requestFactory.create(request);

            // if the request period is not overlapped by another
            if(!isRequestOverlapped(requestDTO)) {
                request = requestRepository.save(request);
                return request;
            } else {
                throw new RequestOverlappingException();
            }
        }

        return null;
    }

    /*
     *  Insert flow for normal request
     */
    public boolean insertNormal(UserDTO requester, LocalDate startDate, LocalDate endDate) throws NotEnoughDaysException, RequestOverlappingException {
        float remainingPool = historyService.getHolidaysPool(requester.getId(), null);
        float currentPool = DurationCalculator.calculate(requester, startDate, endDate, holidayService);

        if (remainingPool >= currentPool) {
            Request.Type type = Request.Type.NORMAL;
            Request request = insert(requester, startDate, endDate, type, null, Request.Status.PENDING);

            if (request != null) {
                // create acceptances for leaders
                Set<String> leadersMails = requester.getTeams().stream()
                        .map(t -> t.getLeader().getPrincipalName())
                        .collect(Collectors.toSet());
                for (String leaderMail : leadersMails) {
                    User leader = userRepository.findFirstByMail(leaderMail);
                    acceptanceService.insert(request, leader);
                }

                return true;
            }
        } else {
            throw new NotEnoughDaysException();
        }

        return false;
    }

    /*
     *  Insert flow for occasional request
     */
    public boolean insertOccasional(UserDTO requester, LocalDate startDate, Request.OccasionalType info) throws RequestOverlappingException {
        Request.Type type = Request.Type.OCCASIONAL;
        LocalDate endDate = holidayService.getWorkingDate(startDate, info.getDurationDays());

        Request request = this.insert(requester, startDate, endDate, type, info, Request.Status.ACCEPTED);

        if (request != null) {
            RequestDTO requestDTO = requestFactory.create(request);

            // Sending info to admins and leaders, then remaining mail to requester
            eventPublisher.publishEvent(new OccasionalInfoEvent(this, requestDTO));
            eventPublisher.publishEvent(new OccasionalResponseEvent(this, requestDTO));

            // Send info to history
            historyService.insertWithComment(requestDTO, info.getInfo(), requestDTO.getRequester());

            return true;
        }
        return false;
    }

    public boolean accept(long id, long deciderId) {
        List<AcceptanceDTO> acceptances = acceptanceService.getAcceptancesFromRequest(id);
        boolean success = true;

        for (AcceptanceDTO acceptance : acceptances) {
            if (!acceptanceService.accept(acceptance.getId(), deciderId)) {
                success = false;
            }
        }

        // change the status to accepted
        Request request = requestRepository.findOne(id);
        request.setStatus(Request.Status.ACCEPTED);
        requestRepository.save(request);

        return success;
    }

    public boolean reject(long id, long deciderId) {
        List<AcceptanceDTO> acceptances = acceptanceService.getAcceptancesFromRequest(id);
        boolean success = true;

        for (AcceptanceDTO acceptance : acceptances) {
            if (!acceptanceService.reject(acceptance.getId(), deciderId)) {
                success = false;
            }
        }

        // change the status to rejected
        Request request = requestRepository.findOne(id);
        request.setStatus(Request.Status.REJECTED);
        requestRepository.save(request);

        return success;
    }

    public boolean cancel(long id) {
        RequestDTO requestDTO = this.getRequest(id);

        boolean success = true;
        boolean cancelingAfterAccepting = true;

        List<AcceptanceDTO> acceptances = acceptanceService.getAcceptancesFromRequest(id);
        // TODO: another way to mark requests CANCELED (maybe PENDING, REJECTED and ACCEPTED also)
        if(acceptances.isEmpty()) {
            acceptances.add(acceptanceService.insertCancelAcceptance(requestDTO));
        }

        // canceling all acceptances
        for (AcceptanceDTO acceptance : acceptances) {
            if (acceptance.getDecider() == null) {
                cancelingAfterAccepting = false;
            }

            if (!acceptanceService.reject(acceptance.getId(), acceptance.getRequest().getRequester().getId())) {
                success = false;
            }
        }

        // change the status to rejected
        Request request = requestRepository.findOne(id);
        request.setStatus(Request.Status.CANCELLED);
        requestRepository.save(request);

        // reverting days pool
        // TODO: Localize it!
        if (success && cancelingAfterAccepting && requestDTO.getType() == Request.Type.NORMAL) {
            historyService.insertReversedWithComment(requestDTO, "Anulowanie");
        } else if (success && requestDTO.getType() == Request.Type.OCCASIONAL) {
            historyService.insertReversedWithComment(requestDTO, "Anulowanie: " + requestDTO.getTypeInfo().getInfo());
        }

        return success;
    }

    boolean isValidRequestByAcceptance(long acceptanceId) {

        Acceptance acceptance = acceptanceRepository.findOne(acceptanceId);

        User requester = acceptance.getRequest().getRequester();
        RequestDTO requestDTO = requestFactory.create(acceptance.getRequest());
        float userHolidaysPool = historyService.getHolidaysPool(requester.getId(), null);
        float requestedPool = DurationCalculator.calculate(requestDTO, holidayService);
        return userHolidaysPool >= requestedPool;
    }

    boolean isValidRequestByRequest(long requestId) {

        Acceptance acceptance = acceptanceRepository.findByRequestId(requestId).get(0);

        User requester = acceptance.getRequest().getRequester();
        RequestDTO requestDTO = requestFactory.create(acceptance.getRequest());
        float userHolidaysPool = historyService.getHolidaysPool(requester.getId(), null);
        float requestedPool = DurationCalculator.calculate(requestDTO, holidayService);
        return userHolidaysPool >= requestedPool;
    }

    void checkForActions(Request request) {
        RequestDTO requestDTO = requestFactory.create(request);
        List<AcceptanceDTO> acceptances = acceptanceService.getAcceptancesFromRequest(request.getId());

        int accepted = 0;
        for (AcceptanceDTO acceptance : acceptances) {
            if (acceptance.isAccepted()) {
                accepted++;
            }
        }

        // if request is accepted
        if (accepted == acceptances.size()) {
            request.setStatus(Request.Status.ACCEPTED);
            requestRepository.save(request);

            eventPublisher.publishEvent(new RequestAcceptedEvent(this, requestDTO));
            historyService.insert(requestDTO);
        }
    }
}
