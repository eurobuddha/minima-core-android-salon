package com.eurobuddha.salon;
import android.content.Context;
import android.os.Looper;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonAdoptionTest {
    MainActivity activity;NodeApi node;
    final List<NodeApi.Cb> replies=new ArrayList<>();
    private Field field(String name)throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
    private void check()throws Exception {Method m=MainActivity.class.getDeclaredMethod("checkIdentityAdoption");m.setAccessible(true);m.invoke(activity);}
    private void reply(JSONObject value){replies.get(replies.size()-1).onResult(value);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private JSONObject balance(boolean found)throws Exception {
        JSONArray rows=new JSONArray();if(found)rows.put(new JSONObject().put("tokenid","0x"+"ab".repeat(32)).put("token",new JSONObject().put("salon","1").put("handle","artist").put("url","").toString()));
        return new JSONObject().put("status",true).put("response",rows);
    }
    @Before @SuppressWarnings({"unchecked","rawtypes"}) public void setup()throws Exception {
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.Theme_StateNFT);
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();
        node=mock(NodeApi.class);field("node").set(activity,node);field("nodeUp").set(activity,true);
        field("screen").set(activity,Enum.valueOf((Class)field("screen").getType(),"HOSTING"));
        doAnswer(i->{assertEquals("balance",i.getArgument(0));replies.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any());
    }
    @After public void cleanup()throws Exception {((ExecutorService)field("io").get(activity)).shutdownNow();}
    @Test public void timeoutLeavesTheLookupRetryableAndOnlyOneRunsAtATime()throws Exception {
        check();check();assertEquals(1,replies.size());replies.get(0).onError("timeout");Shadows.shadowOf(Looper.getMainLooper()).idle();
        check();assertEquals(2,replies.size());reply(balance(true));assertTrue(SalonStore.hasIdentity(activity));
        assertEquals("artist",SalonStore.get(activity,"handle"));check();assertEquals(2,replies.size());
    }
    @Test public void malformedReplyCannotCountAsCompletedAbsence()throws Exception {
        for(JSONObject invalid:new JSONObject[]{null,new JSONObject(),new JSONObject().put("status",false),new JSONObject().put("status",true).put("response",new JSONArray().put(JSONObject.NULL))}) {
            check();reply(invalid);assertFalse((boolean)field("adoptChecked").get(activity));
        }
        check();reply(balance(false));assertTrue((boolean)field("adoptChecked").get(activity));int count=replies.size();check();assertEquals(count,replies.size());
    }
    @Test public void changedAccountRejectsLateTokenAdoption()throws Exception {
        check();SalonStore.put(activity,"handle","replacement");reply(balance(true));assertEquals("replacement",SalonStore.get(activity,"handle"));assertEquals("",SalonStore.get(activity,"tokenid"));
    }
    @Test public void oldDuplicateReplyCannotConsumeTheNewRequest()throws Exception {
        check();NodeApi.Cb old=replies.get(0);old.onError("timeout");Shadows.shadowOf(Looper.getMainLooper()).idle();check();
        old.onResult(balance(true));Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(SalonStore.hasIdentity(activity));
        reply(balance(false));assertTrue((boolean)field("adoptChecked").get(activity));
    }
}
