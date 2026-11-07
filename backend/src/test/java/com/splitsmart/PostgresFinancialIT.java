package com.splitsmart;

import java.math.BigDecimal;
import java.util.*;
import com.splitsmart.dto.*;
import com.splitsmart.entity.*;
import com.splitsmart.repository.*;
import com.splitsmart.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("postgres-it")
@Transactional
class PostgresFinancialIT {
    @Autowired UserRepository users;
    @Autowired ExpenseGroupRepository groups;
    @Autowired GroupMemberRepository members;
    @Autowired ExpenseService expenses;
    @Autowired SettlementService settlements;
    @Autowired SettlementSuggestionService suggestions;
    @Autowired DashboardService dashboard;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private List<User> people;
    private ExpenseGroup group;
    @BeforeEach void fixtures() {
        people=new ArrayList<>();
        for(int i=0;i<4;i++) people.add(users.saveAndFlush(new User("Synthetic",UUID.randomUUID()+"@example.invalid","synthetic")));
        group=groups.saveAndFlush(new ExpenseGroup("Synthetic compatibility",people.getFirst()));
        people.forEach(u->members.saveAndFlush(new GroupMember(group,u)));
    }
    private void expense(int payer,int debtor,String amount) {
        expenses.createExpense(group.getId(),people.get(payer).getId().toString(),
                new CreateExpenseRequest("Synthetic",new BigDecimal(amount),people.get(payer).getId(),"INR",
                        List.of(new CreateExpenseSplitRequest(people.get(debtor).getId(),new BigDecimal(amount)))),"\""+UUID.randomUUID()+"\"");
    }
    @Test void circularDebtsPreserveFourObligationsAndSuggestZero() {
        for(int i=0;i<4;i++) expense((i+1)%4,i,"50.00");
        var result=suggestions.getSuggestions(group.getId(),people.getFirst().getId().toString());
        assertEquals(new BigDecimal("0.00"),result.totalSuggestedAmount());
        assertTrue(result.suggestions().isEmpty());
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM expenses WHERE group_id=?",Integer.class,group.getId()));
        var view=dashboard.get(people.getFirst().getId().toString());
        assertEquals(new BigDecimal("0.00"),view.netPosition());
        assertEquals(4,view.activity().size());
        assertTrue(view.activity().stream().allMatch(a->a.recordedAt()!=null));
    }
    @Test void settlementAndDashboardKeepExactCents() {
        expense(0,1,"50.01");
        assertEquals(new BigDecimal("50.01"),dashboard.get(people.get(1).getId().toString()).owed());
        settlements.createSettlement(group.getId(),people.get(1).getId().toString(),
                new CreateSettlementRequest(people.get(1).getId(),people.getFirst().getId(),new BigDecimal("50.01"),UUID.randomUUID().toString(),null));
        assertEquals(new BigDecimal("0.00"),suggestions.getSuggestions(group.getId(),people.getFirst().getId().toString()).totalSuggestedAmount());
        assertEquals(new BigDecimal("0.00"),dashboard.get(people.get(1).getId().toString()).owed());
    }
    @Test void emailOwnershipLookupPreservesCaseAndAccentEquivalence() {
        String suffix=UUID.randomUUID()+"@example.invalid";
        User user=users.saveAndFlush(new User("Synthetic","r\u00e9sum\u00e9"+suffix,"synthetic"));
        assertEquals(user.getId(),users.findByEmail(("resume"+suffix).toUpperCase(java.util.Locale.ROOT)).orElseThrow().getId());
    }
    @Test void settlementUuidCaseDoesNotBypassDuplicateProtection() {
        expense(0,1,"50.01");
        String key="abcdef01-abcd-4abc-abcd-abcdef012345";
        settlements.createSettlement(group.getId(),people.get(1).getId().toString(),
                new CreateSettlementRequest(people.get(1).getId(),people.getFirst().getId(),new BigDecimal("10.00"),key,null));
        var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->
            settlements.createSettlement(group.getId(),people.get(1).getId().toString(),
                new CreateSettlementRequest(people.get(1).getId(),people.getFirst().getId(),new BigDecimal("10.00"),key.toUpperCase(Locale.ROOT),null)));
        assertEquals(409,failure.getStatusCode().value());
    }
}
