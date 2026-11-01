package com.canalagoz.tvpicker;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

/** Version, license, privacy statement and links. Plain text: most TVs have no browser to open links. */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Can't happen: we are asking about ourselves.
        }
        ((TextView) findViewById(R.id.about_version)).setText(getString(R.string.about_version, version));

        // Focusable so the D-pad can scroll the page with Up/Down.
        findViewById(R.id.about_content).requestFocus();
    }
}
