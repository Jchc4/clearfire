package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads Android TV Continue Watching (WatchNext) and Preview Channel programs (e.g. Nuvio and OwnTV).
 */
public final class TvWatchNext {
    private static final String TAG = "TvWatchNext";

    public static final class Item {
        public final long id;
        public final String packageName;
        public final String title;
        public final String subtitle;
        public final String posterArtUri;
        public final String intentUri;
        public final long progressMs;
        public final long durationMs;
        public final boolean isLive;
        public Bitmap bitmap;

        public Item(long id, String packageName, String title, String subtitle,
                    String posterArtUri, String intentUri, long progressMs, long durationMs, boolean isLive) {
            this.id = id;
            this.packageName = packageName;
            this.title = title != null ? title : "";
            this.subtitle = subtitle != null ? subtitle : "";
            this.posterArtUri = posterArtUri != null ? posterArtUri : "";
            this.intentUri = intentUri != null ? intentUri : "";
            this.progressMs = progressMs;
            this.durationMs = durationMs;
            this.isLive = isLive;
        }

        public void launch(Context context) {
            if (!intentUri.isEmpty()) {
                try {
                    Intent intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to launch item: " + title, e);
                }
            }
            try {
                String pkg = !packageName.isEmpty() ? packageName : "com.nuvio.tv";
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to launch package: " + packageName, e);
            }
        }
    }

    public interface Callback {
        void onLoaded(List<Item> items);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile boolean broadcastSent = false;

    private TvWatchNext() {}

    /**
     * Queries Watch Next programs and Preview programs from TvContract asynchronously.
     */
    public static void loadItems(Context context, Callback callback) {
        if (!broadcastSent) {
            broadcastSent = true;
            try {
                Intent initIntent = new Intent("android.media.tv.action.INITIALIZE_PROGRAMS");
                initIntent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                context.sendBroadcast(initIntent);
            } catch (Exception e) {
                Log.w(TAG, "Failed to broadcast INITIALIZE_PROGRAMS", e);
            }
        }
        EXECUTOR.execute(() -> {
            Log.i(TAG, "Starting TvWatchNext loader...");
            List<Item> result = new ArrayList<>();
            ContentResolver resolver = context.getContentResolver();

            try {
                try (Cursor cursor = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, null, null, null, null)) {
                    int count = cursor != null ? cursor.getCount() : -1;
                    Log.i(TAG, "WatchNextPrograms count: " + count);
                    if (cursor != null) {
                        int idIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms._ID);
                        int pkgIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME);
                        int titleIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_TITLE);
                        int epIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE);
                        int descIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION);
                        int posterIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI);
                        int intentIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTENT_URI);
                        int posIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                        int durIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS);

                        while (cursor.moveToNext() && result.size() < 20) {
                            long id = idIdx >= 0 ? cursor.getLong(idIdx) : 0;
                            String pkg = pkgIdx >= 0 ? cursor.getString(pkgIdx) : "";
                            String title = titleIdx >= 0 ? cursor.getString(titleIdx) : "";
                            String ep = epIdx >= 0 ? cursor.getString(epIdx) : "";
                            String desc = descIdx >= 0 ? cursor.getString(descIdx) : "";
                            String poster = posterIdx >= 0 ? cursor.getString(posterIdx) : "";
                            String intent = intentIdx >= 0 ? cursor.getString(intentIdx) : "";
                            long pos = posIdx >= 0 ? cursor.getLong(posIdx) : 0;
                            long dur = durIdx >= 0 ? cursor.getLong(durIdx) : 0;

                            String sub = !ep.isEmpty() ? ep : desc;
                            Log.i(TAG, "Found WatchNext item: " + title + " (" + pkg + ")");
                            result.add(new Item(id, pkg, title, sub, poster, intent, pos, dur, false));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error querying WatchNextPrograms", e);
            }

            try {
                try (Cursor chCursor = resolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)) {
                    int chCount = chCursor != null ? chCursor.getCount() : -1;
                    Log.i(TAG, "Channels count: " + chCount);
                    if (chCursor != null) {
                        int idIdx = chCursor.getColumnIndex(TvContract.Channels._ID);
                        int pkgIdx = chCursor.getColumnIndex(TvContract.Channels.COLUMN_PACKAGE_NAME);
                        int nameIdx = chCursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME);

                        while (chCursor.moveToNext()) {
                            long chId = idIdx >= 0 ? chCursor.getLong(idIdx) : 0;
                            String pkg = pkgIdx >= 0 ? chCursor.getString(pkgIdx) : "";
                            String name = nameIdx >= 0 ? chCursor.getString(nameIdx) : "";
                            Log.i(TAG, "Channel: " + name + " (id=" + chId + ", pkg=" + pkg + ")");

                            Uri channelProgramsUri = TvContract.buildPreviewProgramsUriForChannel(chId);
                            try (Cursor progCursor = resolver.query(channelProgramsUri, null, null, null, null)) {
                                if (progCursor != null) {
                                    int pIdIdx = progCursor.getColumnIndex(TvContract.PreviewPrograms._ID);
                                    int pTitleIdx = progCursor.getColumnIndex(TvContract.PreviewPrograms.COLUMN_TITLE);
                                    int pDescIdx = progCursor.getColumnIndex(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION);
                                    int pPosterIdx = progCursor.getColumnIndex(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI);
                                    int pIntentIdx = progCursor.getColumnIndex(TvContract.PreviewPrograms.COLUMN_INTENT_URI);

                                    while (progCursor.moveToNext() && result.size() < 30) {
                                        long pId = pIdIdx >= 0 ? progCursor.getLong(pIdIdx) : 0;
                                        String pTitle = pTitleIdx >= 0 ? progCursor.getString(pTitleIdx) : "";
                                        String pDesc = pDescIdx >= 0 ? progCursor.getString(pDescIdx) : "";
                                        String pPoster = pPosterIdx >= 0 ? progCursor.getString(pPosterIdx) : "";
                                        String pIntent = pIntentIdx >= 0 ? progCursor.getString(pIntentIdx) : "";

                                        Log.i(TAG, "Found Channel program: " + pTitle + " in channel " + name);
                                        result.add(new Item(pId, pkg, pTitle, pDesc, pPoster, pIntent, 0, 0, true));
                                    }
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Error querying programs for channel " + chId, e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error querying Channels", e);
            }

            if (result.isEmpty()) {
                result.add(new Item(1, "com.nuvio.tv", "Resume Watching in Nuvio", "Click to resume last stream", "", "", 1800000, 3600000, false));
            }

            Log.i(TAG, "Total items loaded: " + result.size());

            for (Item item : result) {
                if (!item.posterArtUri.isEmpty()) {
                    try {
                        Uri uri = Uri.parse(item.posterArtUri);
                        if ("content".equalsIgnoreCase(uri.getScheme()) || "android.resource".equalsIgnoreCase(uri.getScheme())) {
                            try (InputStream is = resolver.openInputStream(uri)) {
                                if (is != null) {
                                    item.bitmap = BitmapFactory.decodeStream(is);
                                }
                            }
                        } else if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                            URL url = new URL(item.posterArtUri);
                            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                            conn.setConnectTimeout(3000);
                            conn.setReadTimeout(3000);
                            try (InputStream is = conn.getInputStream()) {
                                item.bitmap = BitmapFactory.decodeStream(is);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            MAIN_HANDLER.post(() -> callback.onLoaded(Collections.unmodifiableList(result)));
        });
    }
}
