package io.certacota.engine.service.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.certacota.engine.core.domain.BalanceAuditLog;
import io.certacota.engine.core.repository.BalanceAuditLogRepository;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class AuditLogSteps {

    @Autowired
    private BalanceAuditLogRepository auditLogRepository;

    @Autowired
    private SharedContext sharedContext;

    @Then("the balance_audit_log table contains exactly 1 entry for account {string}")
    public void auditLogContainsOneEntry(String accountId) {
        List<BalanceAuditLog> entries = auditLogRepository.findByAccountId(accountId);
        assertThat(entries).hasSize(1);
    }

    @And("the entry has operation {string} and balance_after {bigdecimal}")
    public void entryHasOperationAndBalanceAfter(String expectedOperation, BigDecimal expectedBalanceAfter) {
        String accountId = extractAccountIdFromLastResponse();
        List<BalanceAuditLog> entries = auditLogRepository.findByAccountId(accountId);
        assertThat(entries).hasSize(1);
        BalanceAuditLog entry = entries.get(0);
        assertThat(entry.getOperation()).isEqualTo(expectedOperation);
        assertThat(entry.getBalanceAfter().compareTo(expectedBalanceAfter)).isEqualTo(0);
    }

    private String extractAccountIdFromLastResponse() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readTree(sharedContext.getLastResponse().getBody()).get("id").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract account id from response: " + sharedContext.getLastResponse().getBody(), e);
        }
    }
}
