package jp.photoviewer.dbx.dropbox;

import android.content.Context;

import androidx.annotation.Nullable;

import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.android.Auth;
import com.dropbox.core.android.AuthActivity;
import com.dropbox.core.json.JsonReadException;
import com.dropbox.core.oauth.DbxCredential;
import com.dropbox.core.v2.DbxClientV2;

import java.util.Arrays;
import java.util.List;

import jp.photoviewer.dbx.BuildConfig;
import jp.photoviewer.dbx.Prefs;

/** 認証情報の保存と DbxClientV2 の生成を担当する。 */
public class DropboxClientProvider {

    /** 閲覧専用なので読み取りスコープだけを要求する。 */
    private static final List<String> SCOPES = Arrays.asList(
            "files.metadata.read",
            "files.content.read");

    private final Prefs prefs;
    private final DbxRequestConfig config = DbxRequestConfig.newBuilder("DbxPhotoViewer/1.0")
            .withAutoRetryEnabled(3) // 429(レート制限)や一時エラーは SDK 側で待って再試行
            .build();

    private static final String KEY_AWAITING_AUTH = "awaiting_auth";

    private DbxClientV2 client;
    private DbxCredential credential;

    public DropboxClientProvider(Prefs prefs) {
        this.prefs = prefs;
    }

    public boolean hasAppKey() {
        return !BuildConfig.DROPBOX_APP_KEY.isEmpty();
    }

    public synchronized boolean isLoggedIn() {
        return getClient() != null;
    }

    /** ログイン済みならクライアントを返す。未ログインなら null。 */
    @Nullable
    public synchronized DbxClientV2 getClient() {
        if (client == null) {
            String json = prefs.getCredential();
            if (json != null) {
                try {
                    credential = DbxCredential.Reader.readFully(json);
                    // DbxCredential を渡すと、期限切れのアクセストークンは SDK が自動で更新する
                    client = new DbxClientV2(config, credential);
                } catch (JsonReadException e) {
                    prefs.setCredential(null);
                }
            }
        }
        return client;
    }

    /**
     * SDK を通さずに API を呼ぶとき用の、有効なアクセストークン。
     * 期限切れが近ければリフレッシュトークンで更新する(SDK のクライアントと同じ認証情報を共有)。
     */
    public synchronized String getFreshAccessToken() throws java.io.IOException {
        if (getClient() == null || credential == null) {
            throw new ThumbnailApi.AuthException("not logged in");
        }
        if (credential.aboutToExpire()) {
            try {
                credential.refresh(config);
                prefs.setCredential(DbxCredential.Writer.writeToString(credential));
            } catch (com.dropbox.core.DbxException e) {
                throw new java.io.IOException("token refresh failed", e);
            }
        }
        return credential.getAccessToken();
    }

    public DbxClientV2 requireClient() throws NotLoggedInException {
        DbxClientV2 c = getClient();
        if (c == null) throw new NotLoggedInException();
        return c;
    }

    /** ブラウザ(または Dropbox アプリ)で OAuth2 PKCE 認証を開始する。 */
    public synchronized void startLogin(Context context) {
        // 認証中にプロセスが落ちても結果を受け取れるよう、フラグは永続化する
        setAwaitingAuth(true);
        AuthActivity.result = null;
        Auth.startOAuth2PKCE(context, BuildConfig.DROPBOX_APP_KEY, config, SCOPES);
    }

    /**
     * 認証から戻ったとき(onResume)に呼ぶ。認証結果があれば保存して true を返す。
     * ログアウト後に古い結果を拾わないよう、自分で認証を開始したときだけ結果を読む。
     */
    public synchronized boolean handleAuthResult() {
        if (!isAwaitingAuth()) return false;
        DbxCredential credential = Auth.getDbxCredential();
        if (credential == null) return false;
        setAwaitingAuth(false);
        AuthActivity.result = null;
        prefs.setCredential(DbxCredential.Writer.writeToString(credential));
        client = null;
        this.credential = null;
        return true;
    }

    public synchronized void logout() {
        client = null;
        credential = null;
        setAwaitingAuth(false);
        AuthActivity.result = null;
        prefs.clearSession();
    }

    private boolean isAwaitingAuth() {
        return prefs.raw().getBoolean(KEY_AWAITING_AUTH, false);
    }

    private void setAwaitingAuth(boolean value) {
        prefs.raw().edit().putBoolean(KEY_AWAITING_AUTH, value).commit();
    }

    public static class NotLoggedInException extends Exception {
        public NotLoggedInException() {
            super("Dropbox にログインしていません");
        }
    }
}
