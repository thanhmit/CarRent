package com.carrent.payment;

import com.carrent.payment.provider.HttpVietQrRefundDestinationVerifier;
import com.carrent.payment.provider.RefundLookupProperties;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LocalRefundLookupCheckTest {
    static PaymentTestDatabase db;
    static JdbcTemplate jdbc;
    MockEnvironment env;
    MockRestServiceServer server;
    RefundLookupProperties lookup;
    HttpVietQrRefundDestinationVerifier verifier;

    @BeforeAll static void start() throws Exception {
        db=new PaymentTestDatabase();db.bootstrap(Path.of("scriptDB.sql"));
        jdbc=new JdbcTemplate(new DriverManagerDataSource(db.url,db.username,db.password));
    }
    @AfterAll static void close() throws Exception {if(db!=null)db.close();}
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM payment_channels");
        jdbc.update("INSERT INTO payment_channels(id,user_id,owner_role,provider,purpose,credential_reference,enabled,created_at,updated_at) VALUES(103,2,'OWNER','PAYOS','PAYOUT','lookup-fixture',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        env=new MockEnvironment().withProperty("CARRENT_REFUND_LOOKUP_CHECK_CHANNEL_ID","103")
                .withProperty("CARRENT_REFUND_LOOKUP_CHECK_BANK_BIN","970422")
                .withProperty("CARRENT_REFUND_LOOKUP_CHECK_ACCOUNT_NUMBER","000123456")
                .withProperty("CARRENT_REFUND_LOOKUP_CHECK_EXPECTED_NAME","NGUYEN TEST");
        lookup=new RefundLookupProperties(true,"fixture-lookup-client","fixture-private-lookup-key");
        var builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        verifier=new HttpVietQrRefundDestinationVerifier(lookup,builder.build());
    }
    @AfterEach void verifyHttp(){server.verify();}
    LocalRefundLookupCheck check(){return new LocalRefundLookupCheck(jdbc,env,lookup,verifier);}
    void run(){check().run(new DefaultApplicationArguments());}
    void response(String name){server.expect(requestTo("https://api.vietqr.io/v2/lookup"))
            .andRespond(withSuccess("{\"code\":\"00\",\"data\":{\"accountName\":\""+name+"\"}}",org.springframework.http.MediaType.APPLICATION_JSON));}

    @Test void explicitDiagnosticVerifiesADisabledSourceWithoutWritingOrExposingTheDestination() throws Exception {
        var before=snapshot();assertThat(snapshot()).isEqualTo(before);response("NGUYEN TEST");
        var output=new ByteArrayOutputStream();var original=System.out;
        try(var capture=new PrintStream(output,true,StandardCharsets.UTF_8)){
            System.setOut(capture);run();
        }finally{System.setOut(original);}
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("VERIFIED")
                .doesNotContain("970422","000123456","NGUYEN TEST","fixture-private-lookup-key");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT enabled FROM payment_channels WHERE id=103",Boolean.class)).isFalse();
    }

    @Test void mismatchedNameDoesNotChangeMoneyOrLeakEitherName() throws Exception {
        var before=snapshot();response("OTHER PRIVATE NAME");
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class).hasNoCause()
                .hasMessageNotContaining("OTHER PRIVATE NAME").hasMessageNotContaining("NGUYEN TEST");
        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings={"carrent.payment.maintenance.enabled","carrent.rental.deadlines.enabled"})
    void refusesAnEnabledSchedulerBeforeLookup(String flag) {
        env.setProperty(flag,"true");assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
    }

    @Test void refusesLookupThatHasNotBeenExplicitlyEnabled() {
        lookup=new RefundLookupProperties(false,null,null);
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={"CHANNEL_ID","BANK_BIN","ACCOUNT_NUMBER","EXPECTED_NAME"})
    void refusesMissingPrivateInputsBeforeLookup(String suffix) {
        env.setProperty("CARRENT_REFUND_LOOKUP_CHECK_"+suffix,"");
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={"0","-1","1 OR 1=1","9223372036854775808","999999"})
    void refusesInvalidOrMissingPayoutSourcesBeforeLookup(String id) {
        env.setProperty("CARRENT_REFUND_LOOKUP_CHECK_CHANNEL_ID",id);
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(id);
    }

    @Test void refusesACollectChannelBeforeLookup() {
        jdbc.update("UPDATE payment_channels SET provider='SEPAY',purpose='COLLECT',bank_bin='970422',account_number='000123456' WHERE id=103");
        assertThatThrownBy(this::run).isInstanceOf(IllegalStateException.class);
    }

    static Map<String,List<String>> snapshot() {
        var snapshot=new TreeMap<String,List<String>>();
        for(String table:jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'",String.class)) {
            var rows=jdbc.queryForList("SELECT * FROM `"+table+"`").stream().map(row->{
                var canonical=new TreeMap<String,String>();row.forEach((k,v)->canonical.put(k,v instanceof byte[] bytes?HexFormat.of().formatHex(bytes):Objects.toString(v,"<null>")));return canonical.toString();
            }).sorted().toList();snapshot.put(table,rows);
        }
        return snapshot;
    }
}
