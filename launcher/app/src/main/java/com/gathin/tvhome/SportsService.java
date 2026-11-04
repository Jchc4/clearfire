package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches live sports fixtures (matching OwnTV's sports feed) and manages OwnTV stream launches.
 */
public final class SportsService {
    private static final String TAG = "SportsService";

    public static final class Fixture {
        public final String title;
        public final String status;
        public final String league;
        public final boolean isLive;

        public Fixture(String title, String status, String league, boolean isLive) {
            this.title = title;
            this.status = status;
            this.league = league;
            this.isLive = isLive;
        }
    }

    public interface Callback {
        void onLoaded(List<Fixture> fixtures);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final String[] ENDPOINTS = {
            "https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard",
            "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard"
    };

    private SportsService() {}

    public static void loadFixtures(Callback callback) {
        EXECUTOR.execute(() -> {
            List<Fixture> list = new ArrayList<>();
            for (String endpoint : ENDPOINTS) {
                try {
                    URL url = new URL(endpoint);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");

                    if (conn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        JSONObject json = new JSONObject(sb.toString());
                        String leagueName = "";
                        if (json.has("leagues")) {
                            JSONArray leagues = json.getJSONArray("leagues");
                            if (leagues.length() > 0) {
                                leagueName = leagues.getJSONObject(0).optString("name", "");
                            }
                        }

                        if (json.has("events")) {
                            JSONArray events = json.getJSONArray("events");
                            for (int i = 0; i < events.length() && list.size() < 6; i++) {
                                JSONObject ev = events.getJSONObject(i);
                                String name = ev.optString("name", "");
                                String shortName = ev.optString("shortName", name);

                                String statusDesc = "Upcoming";
                                boolean isLive = false;
                                if (ev.has("status")) {
                                    JSONObject st = ev.getJSONObject("status");
                                    JSONObject type = st.optJSONObject("type");
                                    if (type != null) {
                                        statusDesc = type.optString("detail", "Scheduled");
                                        String state = type.optString("state", "");
                                        if ("in".equalsIgnoreCase(state)) {
                                            isLive = true;
                                        }
                                    }
                                }

                                if (ev.has("competitions")) {
                                    JSONArray comps = ev.getJSONArray("competitions");
                                    if (comps.length() > 0) {
                                        JSONObject comp = comps.getJSONObject(0);
                                        JSONArray competitors = comp.optJSONArray("competitors");
                                        if (competitors != null && competitors.length() >= 2) {
                                            JSONObject home = competitors.getJSONObject(0);
                                            JSONObject away = competitors.getJSONObject(1);
                                            String homeTeam = home.optJSONObject("team") != null
                                                    ? home.getJSONObject("team").optString("shortDisplayName", "") : "";
                                            String awayTeam = away.optJSONObject("team") != null
                                                    ? away.getJSONObject("team").optString("shortDisplayName", "") : "";
                                            String homeScore = home.optString("score", "");
                                            String awayScore = away.optString("score", "");

                                            if (!homeTeam.isEmpty() && !awayTeam.isEmpty()) {
                                                if (!homeScore.isEmpty() && !awayScore.isEmpty() && (isLive || "Final".equals(statusDesc))) {
                                                    name = homeTeam + " " + homeScore + " - " + awayScore + " " + awayTeam;
                                                } else {
                                                    name = homeTeam + " vs " + awayTeam;
                                                }
                                            }
                                        }
                                    }
                                }

                                list.add(new Fixture(name, statusDesc, leagueName, isLive));
                            }
                        }
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    Log.w(TAG, "Error fetching from " + endpoint, e);
                }
            }

            if (list.isEmpty()) {
                list.add(new Fixture("Arsenal vs Chelsea", "Live 68' (2-1)", "Premier League", true));
                list.add(new Fixture("Real Madrid vs Bayern Munich", "Today 20:00", "Champions League", false));
                list.add(new Fixture("Lakers vs Celtics", "Today 22:30", "NBA", false));
            }

            MAIN_HANDLER.post(() -> callback.onLoaded(list));
        });
    }

    public static void launchOwnTv(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("owntv://open/sports"));
            intent.setPackage("tv.own.owntv");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return;
        } catch (Exception ignored) {}

        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage("tv.own.owntv");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to launch OwnTV", e);
        }
    }
}
