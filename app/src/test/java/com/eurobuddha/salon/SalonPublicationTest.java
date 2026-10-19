package com.eurobuddha.salon;
import android.content.Context;
import android.os.Looper;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class SalonPublicationTest {
    Context context; NodeApi node; MockedStatic<SalonRegistry> registry;
    List<NodeApi.Cb> reads; List<SalonRegistry.Announced> sends; List<SalonPublication.Stage> stages;
    SalonPublication operation;
    static final String TOKEN="0x"+"a".repeat(64), URL="keets://"+"b".repeat(64)+"/salon/owner/profile.json";
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();context.getSharedPreferences("salon_publication",0).edit().clear().commit();
        node=mock(NodeApi.class); reads=new ArrayList<>();sends=new ArrayList<>();stages=new ArrayList<>();
        doAnswer(c->{reads.add(c.getArgument(1));return null;}).when(node).cmd(anyString(),any());
        registry=mockStatic(SalonRegistry.class,CALLS_REAL_METHODS);
        registry.when(()->SalonRegistry.announce(eq(node),eq(TOKEN),eq(URL),eq("owner"),any())).thenAnswer(c->{sends.add(c.getArgument(4));return null;});
        operation=make();
    }
    SalonPublication make() { return new SalonPublication(context,node,TOKEN,URL,"owner",()->true,stages::add); }
    @After public void end() { operation.stop();registry.close(); }
    JSONObject response(JSONArray coins)throws Exception { return new JSONObject().put("status",true).put("response",coins); }
    JSONObject coin(String token,String url)throws Exception { return new JSONObject().put("tokenid","0x00").put("state",new JSONObject().put("0",token).put("1",SalonRegistry.hex(url))); }
    void empty()throws Exception { reads.get(reads.size()-1).onResult(response(new JSONArray())); }
    @Test public void matchingListingCompletesWithoutAnotherSend()throws Exception {
        operation.start(true); reads.get(0).onResult(response(new JSONArray().put(coin(TOKEN,URL))));
        assertEquals(SalonPublication.Stage.CONFIRMED,stages.get(stages.size()-1));assertFalse(operation.active());assertTrue(sends.isEmpty());
    }
    @Test public void submissionStaysPendingUntilMatchingChainDataThenClears()throws Exception {
        operation.start(true);empty();assertTrue(SalonPublication.pending(context,TOKEN));assertEquals(1,sends.size());
        operation.start(true);assertEquals(1,sends.size());sends.get(0).done(true,"");empty();
        assertTrue(operation.active());assertFalse(stages.contains(SalonPublication.Stage.CONFIRMED));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15));
        reads.get(reads.size()-1).onResult(response(new JSONArray().put(coin(TOKEN,URL))));
        assertFalse(SalonPublication.pending(context,TOKEN));assertEquals(SalonPublication.Stage.CONFIRMED,stages.get(stages.size()-1));
    }
    @Test public void reopenAndLostResponseNeverResubmit()throws Exception {
        operation.start(true);empty();operation.stop();operation=make();operation.start(true);empty();
        assertEquals(1,sends.size());assertTrue(SalonPublication.pending(context,TOKEN));
        sends.get(0).done(true,"late");assertEquals(2,reads.size());
    }
    @Test public void duplicateRepliesCannotSubmitTwice()throws Exception {
        operation.start(true);empty();empty();assertEquals(1,sends.size());
        sends.get(0).done(true,"");sends.get(0).done(true,"");assertEquals(2,reads.size());
    }
    @Test public void timeoutOffersReadOnlyCheckAndPreservesSubmission()throws Exception {
        operation.start(true);empty();sends.get(0).done(false,"lost reply");
        for(int i=0;i<20;i++) { empty();Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15)); }
        assertFalse(operation.active());assertEquals(SalonPublication.Stage.UNKNOWN,stages.get(stages.size()-1));
        operation.start(true);empty();assertEquals(1,sends.size());
    }
    @Test public void failedReadIsNotAnEmptyRegistryOrPermissionToSend()throws Exception {
        operation.start(true);reads.get(0).onResult(new JSONObject().put("status",false).put("response",new JSONArray()));
        assertTrue(sends.isEmpty());assertFalse(stages.contains(SalonPublication.Stage.CONFIRMED));
    }
    @Test public void explicitRejectionAllowsRetryButLostReplyDoesNot()throws Exception {
        operation.start(true);empty();sends.get(0).rejected("insufficient funds");
        assertFalse(SalonPublication.pending(context,TOKEN));assertFalse(operation.active());
        assertEquals(SalonPublication.Stage.REJECTED,stages.get(stages.size()-1));
        operation.start(true);empty();assertEquals(2,sends.size());
    }
    @Test public void validatesBothStateFormsAndRejectsWrongOrSpentCoins()throws Exception {
        assertFalse(SalonPublication.listed(new JSONArray().put(coin(TOKEN,URL+"x")),TOKEN,URL));
        assertFalse(SalonPublication.listed(new JSONArray().put(coin(TOKEN,URL).put("spent",true)),TOKEN,URL));
        assertFalse(SalonPublication.listed(new JSONArray().put(coin("other",URL)),TOKEN,URL));
        JSONObject c=coin(TOKEN,URL).put("state",new JSONArray().put(new JSONObject().put("port",0).put("data",TOKEN)).put(new JSONObject().put("port",1).put("data",SalonRegistry.hex(URL))));
        assertTrue(SalonPublication.listed(new JSONArray().put(c),TOKEN,URL));
    }
}
