package com.splitsmart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.splitsmart.dto.*;
import com.splitsmart.service.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline portfolio snapshots must agree with the existing financial engine. No DB or Spring context. */
class SyntheticPortfolioContractTest {
    private JsonNode fixtures() throws Exception {
        return new ObjectMapper().readTree(Path.of("../frontend/src/demo/scenarios.json").toFile());
    }
    private BigDecimal decimal(JsonNode n) { return new BigDecimal(n.asText()); }
    @Test void allFiveSnapshotsMatchTheExistingSettlementEngine() throws Exception {
        var f=fixtures();
        for (var entry:f.get("snapshots").properties()) {
            var value=entry.getValue();
            var members=new ArrayList<MemberBalanceResponse>();
            for(int i=0;i<4;i++) members.add(new MemberBalanceResponse((long)i+1,
                f.get("users").get(i).get("name").asText(),f.get("users").get(i).get("email").asText(),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,decimal(value.get("net").get(i))));
            var expenses=mock(ExpenseService.class);
            when(expenses.getGroupBalances(1L,"1")).thenReturn(new GroupBalanceResponse(1L,"INR",decimal(value.get("total")),members));
            var actual=new SettlementSuggestionService(expenses).getSuggestions(1L,"1");
            assertEquals(value.get("transfers").size(),actual.suggestions().size(),entry.getKey());
            for(int i=0;i<actual.suggestions().size();i++) {
                var expected=value.get("transfers").get(i);var transfer=actual.suggestions().get(i);
                assertEquals(expected.get(0).asLong(),transfer.fromUserId());
                assertEquals(expected.get(1).asLong(),transfer.toUserId());
                assertEquals(0,decimal(expected.get(2)).compareTo(transfer.amount()));
            }
        }
    }
    @Test void sourceExpenseSharesAndRepaymentReconcileExactly() throws Exception {
        var f=fixtures();var net=new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO};
        String[] stages={"equal","unequal"};int index=0;
        for(var e:f.get("expenses")) {
            var amount=decimal(e.get("amount"));var sum=BigDecimal.ZERO;
            for(int i=0;i<4;i++){var share=decimal(e.get("split").get(i));sum=sum.add(share);net[i]=net[i].subtract(share);}
            assertEquals(0,amount.compareTo(sum));int payer=e.get("payer").asInt()-1;net[payer]=net[payer].add(amount);
            for(int i=0;i<4;i++)assertEquals(0,net[i].compareTo(decimal(f.get("snapshots").get(stages[index]).get("net").get(i))));
            index++;
        }
        net[2]=net[2].add(new BigDecimal("600.00"));net[0]=net[0].subtract(new BigDecimal("600.00"));
        for(int i=0;i<4;i++)assertEquals(0,net[i].compareTo(decimal(f.get("snapshots").get("settled").get("net").get(i))));
    }
    @Test void fourCircularObligationsRemainAndAllIdentitiesAreFictional() throws Exception {
        var f=fixtures();assertEquals(4,f.get("circle").size());
        var net=new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO};
        for(var debt:f.get("circle")){var amount=decimal(debt.get(2));int from=debt.get(0).asInt()-1,to=debt.get(1).asInt()-1;net[from]=net[from].subtract(amount);net[to]=net[to].add(amount);}
        for(var n:net)assertEquals(0,n.compareTo(BigDecimal.ZERO));
        for(var u:f.get("users"))assertTrue(u.get("email").asText().endsWith("@example.invalid"));
    }
}
