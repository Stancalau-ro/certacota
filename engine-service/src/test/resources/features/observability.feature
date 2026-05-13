Feature: Actuator and metrics endpoints

  Scenario: Health endpoint returns UP against live Postgres
    Given the application is started with a live Postgres instance via Testcontainers
    When I request GET /actuator/health
    Then the response status is 200
    And the response body contains '"status":"UP"'

  Scenario: Prometheus metrics endpoint is reachable
    Given the application is running
    When I request GET /actuator/prometheus
    Then the response status is 200
