package com.carrent.payment;

import com.carrent.payment.provider.HttpVietQrRefundDestinationVerifier;
import com.carrent.payment.provider.RefundDestinationVerifier;
import com.carrent.payment.provider.RefundLookupProperties;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VietQrRecipientIntegrationTest {
    static PaymentFlowFixture f;
    static MockRestServiceServer server;
    long refund;
    @BeforeAll static void start() throws Exception {f=new PaymentFlowFixture(true,false,Configuration.class);}
    @AfterAll static void close() throws Exception {if(f!=null)f.close();}
    @BeforeEach void seed() throws Exception {
        f.reset();server.reset();
        var intent=f.payments.openAttempt(201L,f.actor(4));
        f.app.getBean(ReceiptProcessor.class).process(f.receive(9001,intent.code(),"600000"));
        long receipt=f.jdbc.queryForObject("SELECT id FROM payment_receipts",Long.class);
        refund=f.app.getBean(RefundService.class).request("CUSTOMER_CANCEL:1:"+receipt,receipt,540000,RefundReason.CUSTOMER_CANCEL);
    }
    @AfterEach void verifyRequests(){server.verify();}
    String url(){return "/payment/refunds/"+refund;}

    @Test void confirmationWithoutLookupChangesOnlyTheRefundAndNeverCallsVietQr() throws Exception {
        var before=snapshot();
        f.mvc.perform(post(url()+"/recipient").with(user(f.actor(4))).with(csrf())
                .param("bankBin","970422").param("accountNumber","000123456")
                .param("confirmed","true"))
                .andExpect(status().is3xxRedirection());
        var after=snapshot();before.forEach((table,rows)->{if(!table.equals("refunds"))assertThat(after.get(table)).isEqualTo(rows);});
        var row=f.jdbc.queryForMap("SELECT amount,status,payout_channel_id,recipient_user_id,account_number,account_name,ready_at,due_at FROM refunds WHERE id=?",refund);
        assertThat(row).containsEntry("status","READY").containsEntry("amount",540000L)
                .containsEntry("payout_channel_id",103L).containsEntry("recipient_user_id",4L)
                .containsEntry("account_number","000123456").containsEntry("account_name",null);
        assertThat(row.get("ready_at")).isEqualTo(java.time.LocalDateTime.of(2026,10,30,3,0));
        assertThat(row.get("due_at")).isEqualTo(java.time.LocalDateTime.of(2026,10,30,4,0));
        assertThat(f.count("payout_attempts")).isZero();
    }

    @Test void removedPreviewRouteCannotTriggerALookupOrChangeData() throws Exception {
        var before=snapshot();
        f.mvc.perform(post(url()+"/recipient/preview").with(user(f.actor(4))).with(csrf())
                .param("bankBin","970422").param("accountNumber","000123456"))
                .andExpect(status().isNotFound());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void wrongRecipientMissingCsrfAndMissingConfirmationNeverReachLookup() throws Exception {
        var before=snapshot();
        f.mvc.perform(post(url()+"/recipient").with(user(f.actor(5))).with(csrf())
                .param("bankBin","970422").param("accountNumber","000123456")).andExpect(status().isForbidden());
        f.mvc.perform(post(url()+"/recipient").with(user(f.actor(4)))
                .param("bankBin","970422").param("accountNumber","000123456")).andExpect(status().isForbidden());
        f.mvc.perform(post(url()+"/recipient").with(user(f.actor(4))).with(csrf())
                .param("bankBin","970422").param("accountNumber","000123456")).andExpect(status().isConflict());
        assertThat(snapshot()).isEqualTo(before);
    }

    static Map<String,List<String>> snapshot(){
        var result=new TreeMap<String,List<String>>();
        for(String table:f.jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)){
            result.put(table,f.jdbc.queryForList("SELECT * FROM `"+table+"`").stream().map(row->{
                var canonical=new TreeMap<String,String>();row.forEach((k,v)->canonical.put(k,v instanceof byte[] bytes?HexFormat.of().formatHex(bytes):Objects.toString(v,"<null>")));return canonical.toString();
            }).sorted().toList());
        }return result;
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class Configuration {
        @Bean @Primary RefundDestinationVerifier httpLookup(){
            var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
            return new HttpVietQrRefundDestinationVerifier(new RefundLookupProperties(true,"fixture-client","fixture-private-lookup-key"),builder.build());
        }
    }
}
