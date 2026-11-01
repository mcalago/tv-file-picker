package com.canalagoz.tvpicker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The screen people see when they open the app. It walks through the two things that must
 * be done once, with a green check next to each finished step:
 *  1. allow access to all files (done from the system settings screen, no computer needed);
 *  2. make this app the default for file requests (answer "Always" when Android asks).
 */
public class SetupActivity extends Activity {

    private static final int REQUEST_TRY_PICKER = 1;
    private static final int REQUEST_LEGACY_STORAGE = 2;

    private TextView step1Badge;
    private TextView step2Badge;
    private Button step1Button;
    private Button step2Button;
    private View doneTitle;
    private View doneDesc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        step1Badge = findViewById(R.id.step1_badge);
        step2Badge = findViewById(R.id.step2_badge);
        step1Button = findViewById(R.id.step1_button);
        step2Button = findViewById(R.id.step2_button);
        doneTitle = findViewById(R.id.done_title);
        doneDesc = findViewById(R.id.done_desc);

        step1Button.setOnClickListener(v -> requestFileAccess());
        step2Button.setOnClickListener(v -> tryPicker());
        findViewById(R.id.about_button).setOnClickListener(
                v -> startActivity(new Intent(this, AboutActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** True when this app may read and write all files (the picker can't work without it). */
    static boolean hasFileAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** True when file requests go straight to this app, without the "which app?" chooser. */
    private boolean isDefaultPicker() {
        ResolveInfo info = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && getPackageName().equals(info.activityInfo.packageName);
    }

    private void refresh() {
        boolean access = hasFileAccess(this);
        boolean isDefault = isDefaultPicker();

        showStep(step1Badge, access, "1");
        showStep(step2Badge, isDefault, "2");

        int doneVisibility = access && isDefault ? View.VISIBLE : View.GONE;
        doneTitle.setVisibility(doneVisibility);
        doneDesc.setVisibility(doneVisibility);

        // Put the focus on the next thing to do. Posted so it runs after the first layout pass;
        // on a fresh start the system would otherwise assign focus afterwards and override this.
        Button next = access ? step2Button : step1Button;
        next.post(next::requestFocus);
    }

    private static void showStep(TextView badge, boolean done, String number) {
        badge.setBackgroundResource(done ? R.drawable.bg_step_done : R.drawable.bg_step_todo);
        badge.setText(done ? "✓" : number);
    }

    private void requestFileAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_STORAGE);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            // Many TVs show a list of all apps here, so tell people what to look for.
            Toast.makeText(this, R.string.setup_find_in_list, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException first) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (ActivityNotFoundException second) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.setup_access_unavailable_title)
                        .setMessage(R.string.setup_access_unavailable_msg)
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show();
            }
        }
    }

    /** Fires the same request a game app would, so the "which app?" chooser can be answered with "Always". */
    private void tryPicker() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_TRY_PICKER);
        } catch (ActivityNotFoundException ignored) {
            // Can't happen: this app answers the request itself.
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }
}
