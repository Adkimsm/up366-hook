package io.github.adkimsm.up366;

import android.app.Activity;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 28, 32, 24);

        TextView title = new TextView(this);
        title.setText("UP366 P0 运行时侦察");
        title.setTextSize(20);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setText("默认仅记录 Activity；修改后需重启天学网生效。");
        status.setPadding(0, 16, 0, 16);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        addFlag(root, A366Config.ACTIVITY, "Activity 生命周期");
        addFlag(root, A366Config.NETWORK, "OkHttp 请求与响应元数据");
        addFlag(root, A366Config.WEBVIEW, "WebView 页面与桥对象");
        addFlag(root, A366Config.PROBE, "页面 JS 探针");
        addFlag(root, A366Config.BRIDGE, "JS 桥调用参数");
        addFlag(root, A366Config.QUESTION_METADATA, "题包结构与资源元数据（只读）");

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        refreshStatus();
    }

    private void addFlag(ViewGroup root, String key, String label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTag(key);
        checkBox.setChecked(A366Config.get(this, key));
        checkBox.setOnCheckedChangeListener((button, checked) -> A366Config.set(this, key, checked));
        root.addView(checkBox, new LinearLayout.LayoutParams(-1, -2));
    }

    private void refreshStatus() {
        status.setText("配置已加载。日志目录：Android/data/com.up366.mobile/files/up366/logs/");
    }
}
