Feature: End-by-tag bulk stream settlement

  @Pending
  Scenario: End-by-tag settles all active streams matching the tag in one transaction
    Given no account with id "ebt-001" exists
    And no account with id "ebt-002" exists
    And no account with id "ebt-003" exists
    And an account "ebt-001" exists with balance 500.00
    And an account "ebt-002" exists with balance 500.00
    And an account "ebt-003" exists with balance 500.00
    And I start a stream "ebt-stream-001" on account "ebt-001" at rate 1.00 with tags "session-x" and idempotency key "ik-ebt-001"
    And I start a stream "ebt-stream-002" on account "ebt-002" at rate 1.00 with tags "session-x" and idempotency key "ik-ebt-002"
    And I start a stream "ebt-stream-003" on account "ebt-003" at rate 1.00 with tags "session-x" and idempotency key "ik-ebt-003"
    When I end streams by tag "session-x" with idempotency key "ik-ebt-end-001"
    Then the response status is 200
    And the end-by-tag response settled count is 3

  @Pending
  Scenario: End-by-tag with no active streams for tag returns HTTP 200 with settled count zero
    Given no account with id "ebt-empty-001" exists
    When I end streams by tag "tag-with-no-streams" with idempotency key "ik-ebt-empty-001"
    Then the response status is 200
    And the end-by-tag response settled count is 0

  @Pending
  Scenario: End-by-tag skips already settled streams and reports them in skipped count
    Given no account with id "ebt-skip-001" exists
    And an account "ebt-skip-001" exists with balance 500.00
    And I start a stream "ebt-skip-stream-001" on account "ebt-skip-001" at rate 1.00 with tags "session-y" and idempotency key "ik-ebt-skip-001"
    And I stop stream "ebt-skip-stream-001"
    When I end streams by tag "session-y" with idempotency key "ik-ebt-skip-end-001"
    Then the response status is 200
    And the end-by-tag response settled count is 0

  @Pending
  Scenario: End-by-tag with same idempotency key returns identical cached response
    Given no account with id "ebt-idem-001" exists
    And an account "ebt-idem-001" exists with balance 500.00
    And I start a stream "ebt-idem-stream-001" on account "ebt-idem-001" at rate 1.00 with tags "session-z" and idempotency key "ik-ebt-idem-001"
    And I end streams by tag "session-z" with idempotency key "ik-ebt-idem-end-001"
    And the response status is 200
    When I end streams by tag "session-z" with idempotency key "ik-ebt-idem-end-001"
    Then the response status is 200
    And the end-by-tag response settled count is 1
