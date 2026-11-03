package jp.photoviewer.dbx.ui;

import androidx.activity.EdgeToEdge;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;

import jp.photoviewer.dbx.App;
import jp.photoviewer.dbx.Prefs;
import jp.photoviewer.dbx.R;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // targetSdk 36 では常に全画面(edge-to-edge)表示。余白はレイアウトの fitsSystemWindows で確保する
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private Prefs prefs;
        private Preference folderPref;

        private final ActivityResultLauncher<Intent> pickFolder = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() != android.app.Activity.RESULT_OK || data == null) return;
                    String lower = data.getStringExtra(FolderPickerActivity.EXTRA_PATH_LOWER);
                    String display = data.getStringExtra(FolderPickerActivity.EXTRA_PATH_DISPLAY);
                    if (lower == null || display == null) return;
                    prefs.setFolder(lower, display);
                    updateFolderSummary();
                    App.get().sync().requestSync();
                });

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            prefs = App.get().prefs();

            folderPref = findPreference("pref_folder");
            if (folderPref != null) {
                folderPref.setOnPreferenceClickListener(p -> {
                    if (!App.get().dropbox().isLoggedIn()) {
                        Toast.makeText(requireContext(), "先に Dropbox に接続してください", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    Intent i = new Intent(requireContext(), FolderPickerActivity.class);
                    i.putExtra(FolderPickerActivity.EXTRA_PATH_LOWER, prefs.getFolderPath());
                    i.putExtra(FolderPickerActivity.EXTRA_PATH_DISPLAY, prefs.getFolderDisplay());
                    pickFolder.launch(i);
                    return true;
                });
            }

            Preference clearCache = findPreference("pref_clear_cache");
            if (clearCache != null) {
                clearCache.setOnPreferenceClickListener(p -> {
                    App.get().clearImageCaches();
                    Toast.makeText(requireContext(), "キャッシュを削除しました", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }

            Preference logout = findPreference("pref_logout");
            if (logout != null) {
                logout.setVisible(App.get().dropbox().isLoggedIn());
                logout.setOnPreferenceClickListener(p -> {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("ログアウト")
                            .setMessage("Dropbox との接続を解除し、端末内の一覧とキャッシュを削除します。")
                            .setPositiveButton("ログアウト", (d, w) -> {
                                App app = App.get();
                                app.dropbox().logout();
                                app.sync().clearLocalData();
                                app.clearImageCaches();
                                requireActivity().finish();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return true;
                });
            }
            updateFolderSummary();
        }

        private void updateFolderSummary() {
            if (folderPref != null) folderPref.setSummary(prefs.getFolderDisplay());
        }

        @Override
        public void onResume() {
            super.onResume();
            prefs.raw().registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            prefs.raw().unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if (Prefs.KEY_RECURSIVE.equals(key)) {
                // 探索範囲が変わったので作り直し(SyncManager が設定の変化を検知してDBを捨てる)
                App.get().sync().requestSync();
            }
        }
    }
}
