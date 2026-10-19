package com.eurobuddha.salon;

import android.content.Context;
import android.os.Looper;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Uses real node-response parsing, the asset picker, and edition URL assembly. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class SalonMetadataTest {
    private MainActivity activity; private NodeApi node; private ExecutorService worker;
    private static final String TID="0x"+"ab".repeat(32);
    private static final String NAME="Artwork 中文 Русский Українська";
    private static final String IMAGE="data:image/png;base64,"+"AAAA".repeat(32768);
    private Field field(String name)throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f; }
    private void set(String name,Object value)throws Exception { field(name).set(activity,value); }
    private Object call(String name,Class<?>[] types,Object...args)throws Exception { Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(activity,args); }
    private static JSONObject obj(Object...values)throws Exception { JSONObject o=new JSONObject();for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]);return o; }
    private void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    @SuppressWarnings({"rawtypes","unchecked"})
    @Before public void setup()throws Exception {
        activity=Robolectric.buildActivity(MainActivity.class).get();activity.setTheme(R.style.Theme_StateNFT);
        set("body",new LinearLayout(activity));set("appbar",new LinearLayout(activity));set("navRow",new LinearLayout(activity));
        set("screen",Enum.valueOf((Class)field("screen").getType(),"FEED"));
        ((ExecutorService)field("io").get(activity)).shutdownNow();worker=mock(ExecutorService.class);set("io",worker);
        node=mock(NodeApi.class);set("node",node);set("nodeUp",true);
        activity.getSharedPreferences("salon_identity",Context.MODE_PRIVATE).edit().clear().commit();SalonStore.put(activity,"handle","owner");SalonStore.put(activity,"tokenid","0xowner");
    }
    @After public void cleanup(){android.app.Dialog dialog=ShadowDialog.getLatestDialog();if(dialog!=null)dialog.dismiss();worker.shutdownNow();}
    private List<Object> shapes(JSONObject metadata)throws Exception {
        List<Object> result=new ArrayList<>();result.add(metadata);result.add(metadata.toString());
        for(Object name:new Object[]{metadata,metadata.toString()}) {
            JSONObject token=obj("name",name,"decimals",0);
            for(Object value:new Object[]{token,token.toString()}) {
                result.add(value);JSONObject envelope=obj("token",value,"script","RETURN TRUE");result.add(envelope);result.add(envelope.toString());
            }
        }
        return result;
    }
    private JSONObject pickAndSave(Object raw,String expected)throws Exception {
        set("nodeUp",true);JSONObject reply=obj("status",true,"response",new JSONArray().put(obj("tokenid",TID,"token",raw)));
        String before=reply.toString();doAnswer(c->{((NodeApi.Cb)c.getArgument(1)).onResult(reply);return null;}).when(node).cmd(eq("balance"),any());
        try(MockedStatic<NftProof> proof=mockStatic(NftProof.class,CALLS_REAL_METHODS)) {
            call("pickNftDialog",new Class<?>[]{});idle();android.app.AlertDialog dialog=(android.app.AlertDialog)ShadowDialog.getLatestDialog();assertNotNull(dialog);
            assertEquals(expected,dialog.getListView().getAdapter().getItem(0).toString());
            ListView list=dialog.getListView();list.performItemClick(list.getAdapter().getView(0,null,list),0,0);idle();
            ArgumentCaptor<NftProof.Gen> result=ArgumentCaptor.forClass(NftProof.Gen.class);
            proof.verify(()->NftProof.generate(eq(node),eq(TID),anyString(),result.capture()));
            // Keep this test at the picker/proof-storage boundary, with no edition scan.
            set("nodeUp",false);result.getValue().ok(obj("tokenid",TID,"coinproof","literal proof","sig","literal signature"));idle();
        }
        assertEquals(before,reply.toString());JSONArray nfts=SalonStore.arr(activity,"nfts");return nfts.getJSONObject(nfts.length()-1);
    }
    @Test public void serializedPickerNamesAndLargeEmbeddedIconsRemainLiteralThroughProofSave()throws Exception {
        for(Object raw:shapes(obj("name",NAME,"url",IMAGE,"tokenid","injected inner id"))) {
            JSONObject saved=pickAndSave(raw,NAME);assertEquals(NAME,saved.getString("name"));assertEquals(IMAGE,saved.getString("image"));assertEquals(TID,saved.getString("tokenid"));assertEquals("literal proof",saved.getString("coinproof"));
        }
    }
    @Test public void absentNamesUseShortTokenIdInsteadOfTheImageJson()throws Exception {
        for(Object raw:shapes(obj("url",IMAGE))) {
            JSONObject saved=pickAndSave(raw,Util.shorten(TID));assertEquals(IMAGE,saved.getString("image"));assertEquals(Util.shorten(TID),saved.getString("name"));
        }
    }
    @Test public void plainNamesIncludingMalformedJsonRemainAuthoredText()throws Exception {
        for(String literal:new String[]{"Token","Send","[1,2]","{unfinished",NAME}) {
            assertEquals(literal,pickAndSave(literal,literal).getString("name"));
            assertEquals(literal,pickAndSave(obj("name",literal),literal).getString("name"));
        }
    }
    @Test public void imageAndIconAliasesStillUseExistingIconResolver()throws Exception {
        for(String key:new String[]{"url","icon","image"}) {
            JSONObject saved=pickAndSave(obj("name",obj("name",NAME,key,"ipfs://bafyfixture/cover.png").toString()).toString(),NAME);
            assertEquals("https://ipfs.io/ipfs/bafyfixture/cover.png",saved.getString("image"));
        }
    }
    private JSONArray editions(Object metadata,JSONArray coins)throws Exception { return editions(metadata,coins,true); }
    private JSONArray editions(Object metadata,JSONArray coins,boolean publishable)throws Exception {
        JSONObject balance=obj("status",true,"response",new JSONArray().put(obj("tokenid",TID,"token",metadata)));
        JSONObject held=obj("status",true,"response",coins);String before=balance.toString(),prior=held.toString();
        doAnswer(c->{String cmd=c.getArgument(0);((NodeApi.Cb)c.getArgument(1)).onResult(cmd.startsWith("balance")?balance:held);return null;}).when(node).cmd(anyString(),any());
        List<JSONArray> results=new ArrayList<>();Class<?> callback=Arrays.stream(MainActivity.class.getDeclaredClasses()).filter(c->c.getSimpleName().equals(publishable?"JsonArrayCb":"EditionsCb")).findFirst().get();
        Object proxy=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(o,m,args)->{if(m.getName().equals("ready"))results.add(publishable?(JSONArray)args[0]:new JSONArray((List<?>)args[0]));return null;});
        call(publishable?"captureEditions":"enumerateEditions",new Class<?>[]{String.class,callback},TID,proxy);idle();assertEquals(1,results.size());assertEquals(before,balance.toString());assertEquals(prior,held.toString());return results.get(0);
    }
    @Test public void urlModeCollectionsResolveEachEditionFromSerializedRootNameOrEnvelope()throws Exception {
        JSONObject details=obj("name",NAME,"base","https://example.invalid/art/","ext",".svg","size",12,"mode","url");
        JSONArray coins=new JSONArray().put(obj("state",obj("0","3"))).put(obj("state",new JSONArray().put(obj("port",0,"data","7")))).put(obj("state",obj("0","3")));
        for(Object raw:shapes(details)) {
            JSONArray result=editions(raw,coins);assertEquals(2,result.length());assertEquals("https://example.invalid/art/3.svg",result.getJSONObject(0).getString("url"));assertEquals("https://example.invalid/art/7.svg",result.getJSONObject(1).getString("url"));assertEquals("Edition #3",result.getJSONObject(0).getString("caption"));
        }
    }
    @Test public void outerBaseAndExtensionFallbacksRemainAvailable()throws Exception {
        JSONObject token=obj("name",obj("name",NAME).toString(),"base","https://example.invalid/","ext",".webp");
        for(Object raw:new Object[]{token,token.toString()})assertEquals("https://example.invalid/5.webp",editions(raw,new JSONArray().put(obj("state",obj("0","5")))).getJSONObject(0).getString("url"));
    }
    @Test public void embeddedEditionArtStillWinsOverUrlMetadata()throws Exception {
        String png="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aO/8AAAAASUVORK5CYII=";
        JSONArray result=editions(obj("name",obj("base","https://example.invalid/","ext",".png").toString()),new JSONArray().put(obj("state",obj("0","1","1","["+png+"]"))),false);
        assertEquals(1,result.length());assertEquals("data:image/png;base64,"+png,result.getJSONObject(0).getString("url"));
    }
    @Test public void coercionDoesNotMutateSourceOrParseUnstructuredNames()throws Exception {
        for(Object raw:new Object[]{null,JSONObject.NULL,"{unfinished","[]",NAME})assertNull(Util.asObject(raw));
        JSONObject details=obj("name",NAME),envelope=obj("token",details.toString());String before=envelope.toString();assertEquals(details.toString(),Util.tokenObject(envelope).toString());assertEquals(before,envelope.toString());assertSame(details,Util.asObject(details));
    }
}
