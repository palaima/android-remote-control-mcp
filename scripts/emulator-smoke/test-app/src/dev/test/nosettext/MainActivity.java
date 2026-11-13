package dev.test.nosettext;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    /** An EditText that hides ACTION_SET_TEXT from accessibility services and refuses it. */
    static class NoSetTextEditText extends EditText {
        NoSetTextEditText(Context c) { super(c); }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) return false;
            return super.performAccessibilityAction(action, args);
        }
    }
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        EditText plain = new EditText(this); plain.setHint("plain"); plain.setId(1001);
        EditText nst = new NoSetTextEditText(this); nst.setHint("nosettext"); nst.setId(1002);
        nst.setTextSize(24); plain.setTextSize(24);
        l.addView(nst); l.addView(plain); setContentView(l);
    }
}
