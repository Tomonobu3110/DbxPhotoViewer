package jp.photoviewer.dbx.ui;

import androidx.activity.EdgeToEdge;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

import jp.photoviewer.dbx.App;
import jp.photoviewer.dbx.R;

/** Dropbox のフォルダ階層をたどって、表示対象のフォルダを選ぶ。 */
public class FolderPickerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH_LOWER = "path_lower";
    public static final String EXTRA_PATH_DISPLAY = "path_display";

    private static final class Folder {
        final String lower;
        final String display;
        final String name;

        Folder(String lower, String display, String name) {
            this.lower = lower;
            this.display = display;
            this.name = name;
        }
    }

    private MaterialToolbar toolbar;
    private ProgressBar progress;
    private TextView message;
    private final FolderAdapter adapter = new FolderAdapter();

    private String currentLower = "";
    private String currentDisplay = "";
    private int loadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // targetSdk 36 では常に全画面(edge-to-edge)表示。余白はレイアウトの fitsSystemWindows で確保する
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_folder_picker);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> goUpOrFinish());

        progress = findViewById(R.id.progress);
        message = findViewById(R.id.message);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        Button select = findViewById(R.id.select_button);
        select.setOnClickListener(v -> {
            Intent data = new Intent();
            data.putExtra(EXTRA_PATH_LOWER, currentLower);
            data.putExtra(EXTRA_PATH_DISPLAY, currentDisplay.isEmpty() ? "/" : currentDisplay);
            setResult(RESULT_OK, data);
            finish();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goUpOrFinish();
            }
        });

        String lower = getIntent().getStringExtra(EXTRA_PATH_LOWER);
        String display = getIntent().getStringExtra(EXTRA_PATH_DISPLAY);
        if (lower != null && display != null && !lower.isEmpty()) {
            open(lower, display);
        } else {
            open("", "");
        }
    }

    private void goUpOrFinish() {
        if (currentLower.isEmpty()) {
            finish();
            return;
        }
        open(parentOf(currentLower), parentOf(currentDisplay));
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "" : path.substring(0, slash);
    }

    private void open(String lower, String display) {
        currentLower = lower;
        currentDisplay = display;
        toolbar.setTitle(display.isEmpty() ? "/" : display);
        adapter.setFolders(new ArrayList<>());
        progress.setVisibility(View.VISIBLE);
        message.setVisibility(View.GONE);
        final int gen = ++loadGeneration;

        App.get().io().execute(() -> {
            try {
                DbxClientV2 client = App.get().dropbox().requireClient();
                List<Folder> folders = new ArrayList<>();
                ListFolderResult r = client.files().listFolder(lower);
                while (true) {
                    for (Metadata m : r.getEntries()) {
                        if (m instanceof FolderMetadata && m.getPathLower() != null) {
                            String d = m.getPathDisplay() != null ? m.getPathDisplay() : m.getPathLower();
                            folders.add(new Folder(m.getPathLower(), d, m.getName()));
                        }
                    }
                    if (!r.getHasMore()) break;
                    r = client.files().listFolderContinue(r.getCursor());
                }
                folders.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                runOnUiThread(() -> {
                    if (gen != loadGeneration) return;
                    progress.setVisibility(View.GONE);
                    adapter.setFolders(folders);
                    if (folders.isEmpty()) {
                        message.setText("サブフォルダはありません");
                        message.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen != loadGeneration) return;
                    progress.setVisibility(View.GONE);
                    message.setText("フォルダを読み込めませんでした: " + e.getMessage());
                    message.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private final class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.Holder> {
        private List<Folder> folders = new ArrayList<>();

        @SuppressWarnings("NotifyDataSetChanged")
        void setFolders(List<Folder> list) {
            folders = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_folder, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Folder f = folders.get(position);
            h.name.setText(f.name);
            h.itemView.setOnClickListener(v -> open(f.lower, f.display));
        }

        @Override
        public int getItemCount() {
            return folders.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView name;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.folder_name);
            }
        }
    }
}
