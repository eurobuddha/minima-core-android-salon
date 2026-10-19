package com.eurobuddha.salon;

import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class KeetsRefTest {
    private static final String DRIVE="a".repeat(64), HASH="b".repeat(64);
    private String media(long size,String name) { return "keets://"+DRIVE+"/salon/owner/media/"+size+"-"+HASH+"."+name+"?hash="+HASH; }
    @Test public void malformedReferencesNeverReachTheCommandOrHttpBridge() {
        String good=media(123,"jpg");assertTrue(KeetsRef.isRef(good));
        for(String ref:new String[]{good+";send amount:1",good+"\n",good.replace("owner","../owner"),good.replace("123-","0-"),good.replace("?hash="+HASH,"?hash="+DRIVE),good.replace("keets:","https:"),good.replace("/salon/","/chat/"),good.replace("owner","owner%2f.."),good+"&url=http://127.0.0.1"})
            assertFalse(ref,KeetsRef.isRef(ref));
    }
    @Test public void mediaAndMutableProfilesHaveDifferentIntegrityRules() {
        byte[] bytes="test".getBytes(StandardCharsets.UTF_8);
        String path=KeetsRef.mediaPath("Owner",bytes,"image/jpeg");
        KeetsRef ref=KeetsRef.parse("keets://"+DRIVE+path+"?hash="+KeetsRef.sha(bytes));
        KeetsRef.verify(ref,bytes);
        assertThrows(IllegalArgumentException.class,()->KeetsRef.verify(ref,"evil".getBytes()));
        assertTrue(KeetsRef.parse("keets://"+DRIVE+"/salon/owner/profile.json").profile);
        assertFalse(KeetsRef.isRef("keets://"+DRIVE+"/salon/owner/media/4-"+HASH+".jpg"));
    }
    @Test public void totalIncludesUtf8ProfileAndDeduplicatesRepeatedMedia() throws Exception {
        String ref=media(100,"jpg");
        JSONObject profile=new JSONObject().put("name","é😀").put("avatar",ref).put("banner",ref)
                .put("posts",new JSONArray().put(new JSONObject().put("media",ref)));
        assertEquals(profile.toString().getBytes(StandardCharsets.UTF_8).length+100,KeetsRef.usage(profile));
    }
    @Test public void boundaryCountsMetadataAndEveryMediaItem() throws Exception {
        JSONObject profile=new JSONObject().put("gallery",new JSONArray()
                .put(new JSONObject().put("url",media(12*1024*1024,"mp4")))
                .put(new JSONObject().put("url",media(12*1024*1024,"mp3"))));
        assertThrows(IllegalArgumentException.class,()->KeetsRef.usage(profile));
        JSONObject one=new JSONObject().put("avatar",media(8*1024*1024,"jpg"));
        assertTrue(KeetsRef.usage(one)<KeetsRef.PROFILE_LIMIT);
        assertFalse(KeetsRef.isRef(media(8*1024*1024+1,"jpg")));
        assertFalse(KeetsRef.isRef(media(KeetsRef.PROFILE_LIMIT+1,"mp4")));
    }
    @Test public void replacingAvatarReclaimsItsBudgetAndDoesNotMutateDraft() throws Exception {
        JSONObject p=new JSONObject().put("avatar",media(8*1024*1024,"jpg"))
                .put("gallery",new JSONArray().put(new JSONObject().put("url",media(15*1024*1024,"mp4"))));
        String before=p.toString();byte[] replacement=new byte[2*1024*1024];
        KeetsRef.checkAddition(p,"avatar","owner",replacement,"image/jpeg");
        assertThrows(IllegalArgumentException.class,()->KeetsRef.checkAddition(p,"gallery","owner",replacement,"image/jpeg"));
        assertEquals(before,p.toString());
    }
    @Test public void largeTextAndDeepNestingFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,()->KeetsRef.usage(new JSONObject().put("about","x".repeat(KeetsRef.JSON_LIMIT))));
        JSONObject root=new JSONObject(), last=root;
        for(int i=0;i<40;i++) { JSONObject child=new JSONObject();last.put("child",child);last=child; }
        assertThrows(IllegalArgumentException.class,()->KeetsRef.usage(root));
    }
    @Test public void anOrdinaryExternalLinkIsNotCountedAsLocallyHostedMedia() throws Exception {
        JSONObject p=new JSONObject().put("avatar","https://example.invalid/photo.jpg");
        assertEquals(p.toString().getBytes(StandardCharsets.UTF_8).length,KeetsRef.usage(p));
    }
}
