app.factory("ConfirmationService", function () {
    var showDialog = function (ctrl_name) {
        //show dialog modal with template
        //from userdetails
        // $uibModal.open(
        //     {
        //         animation: true,
        //         templateUrl: 'partials/confirm_days.html',
        //         controller: 'ConfirmDaysCtrl'
        //     }).closed.then(function () {
        //     $scope.$parent.$parent.isCollapsed = true;
        // })

        //from holidays
        // $uibModal.open(
        //     {
        //         animation: true,
        //         templateUrl: 'partials/confirm_reset.html',
        //         controller: 'ConfirmResetCtrl'
        //     }).result.then(function () {
        //     $scope.holidays = API.setUrl('/api/holiday').query({year: $scope.currentYear}, function () {
        //         sort($scope.holidays);
        //         setOptions();
        //     });

    }
    return {

        showConfirmationDialog: function () {

        }
    }
});
