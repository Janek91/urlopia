package info.fingo.urlopia.user;

/**
 * User class which can be sent by the controller
 *
 * @author Tomasz Urbas
 */
public class UserResponse {
    private String id;
    private String mail;
    private String name;

    public UserResponse(UserDTO user) {
        this.id = String.valueOf(user.getId());
        this.mail = user.getMail();
        this.name = user.getName();
    }

    public String getId() {
        return id;
    }

    public String getMail() {
        return mail;
    }

    public String getName() {
        return name;
    }
}
