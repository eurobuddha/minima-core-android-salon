package com.eurobuddha.salon;

import android.widget.EditText;
import java.lang.reflect.*;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class HostingVisibilityTest {
    @Test public void savedSecretsCanBeRevealedWithoutChangingEncryptionAndKeepSelection() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        Method build=MainActivity.class.getDeclaredMethod("cfgSecret",android.widget.LinearLayout.class,JSONObject.class,String.class,String.class);
        build.setAccessible(true);
        try(MockedStatic<Crypt> crypt=mockStatic(Crypt.class)) {
            crypt.when(()->Crypt.decrypt("gcm:stored")).thenReturn("sample-secret");
            for(String key:new String[]{"password","token","jwt"}) {
                JSONObject cfg=new JSONObject().put(key,"gcm:stored");
                android.widget.LinearLayout parent=new android.widget.LinearLayout(activity);
                build.invoke(activity,parent,cfg,key,"Credential");
                android.widget.LinearLayout row=(android.widget.LinearLayout)parent.getChildAt(1);
                EditText input=(EditText)row.getChildAt(0);
                android.widget.TextView eye=(android.widget.TextView)row.getChildAt(1);
                assertEquals("sample-secret",input.getText().toString());
                assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,input.getInputType()&android.text.InputType.TYPE_MASK_VARIATION);
                assertTrue(eye.isFocusable());assertTrue(eye.isClickable());
                assertTrue((input.getImeOptions()&android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0);
                input.setSelection(2,6);
                for(int toggle=0;toggle<4;toggle++) {
                    assertEquals("Show password",eye.getContentDescription().toString());
                    eye.performClick();
                    assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,input.getInputType()&android.text.InputType.TYPE_MASK_VARIATION);
                    assertEquals("Hide password",eye.getContentDescription().toString());
                    assertEquals(2,input.getSelectionStart());assertEquals(6,input.getSelectionEnd());
                    eye.performClick();
                    assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,input.getInputType()&android.text.InputType.TYPE_MASK_VARIATION);
                    assertEquals("sample-secret",input.getText().toString());
                    assertEquals("gcm:stored",cfg.getString(key));
                }
                crypt.verify(()->Crypt.encrypt(anyString()),never());
                crypt.when(()->Crypt.encrypt("replacement-secret")).thenReturn("gcm:replacement");
                input.setText("replacement-secret");
                assertEquals("gcm:replacement",cfg.getString(key));
                crypt.verify(()->Crypt.encrypt("replacement-secret"));
                crypt.clearInvocations();
            }
        } finally {
            Field io=MainActivity.class.getDeclaredField("io");io.setAccessible(true);((ExecutorService)io.get(activity)).shutdownNow();
        }
    }
}
