package io.runtime.mcumgr.sample;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import io.runtime.mcumgr.sample.databinding.ActivityIntroBinding;

public class IntroActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        final ActivityIntroBinding binding = ActivityIntroBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, 0, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        binding.buttonContinue.setOnClickListener(v -> finish());

        binding.introLinkNrfConnectSdk.setOnClickListener(v ->
                open(Uri.parse("https://www.nordicsemi.com/Software-and-Tools/Software/nRF-Connect-SDK"))
        );
        binding.introLinkSmpSample.setOnClickListener(v ->
                open(Uri.parse("https://developer.nordicsemi.com/nRF_Connect_SDK/doc/latest/zephyr/samples/subsys/mgmt/mcumgr/smp_svr/README.html"))
        );
        binding.introLinkSourceCode.setOnClickListener(v ->
                open(Uri.parse("https://github.com/nordicsemi/Android-nRF-Connect-Device-Manager"))
        );

        try {
            final PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_ACTIVITIES);
            binding.version.setText(getResources().getString(R.string.intro_version, info.versionName));
        } catch (final PackageManager.NameNotFoundException e) {
            // Ignore
        }
    }

    private void open(@NonNull final Uri uri) {
        final Intent intent = new Intent(Intent.ACTION_DEFAULT, uri);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
        }
    }

}