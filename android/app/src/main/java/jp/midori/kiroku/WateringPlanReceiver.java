package jp.midori.kiroku;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class WateringPlanReceiver extends BroadcastReceiver {
    static final String ACTION_REMINDER = "jp.midori.kiroku.WATERING_REMINDER";
    static final String PREF_PLAN = "watering_plan_json";
    static final String PREF_LAST_NOTIFIED_DATE = "watering_last_notified";
    private static final String CHANNEL_ID = "watering-reminders";
    static final String PREFS_NAME = "jp.midori.kiroku_preferences";

    static void updatePlan(Context context, String planJson) {
        try {
            JSONObject plan = new JSONObject(planJson);
            String time = plan.optString("time", "");
            if (!time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
                throw new JSONException("Invalid reminder time.");
            }
            JSONArray plants = plan.optJSONArray("plants");
            if (plants == null) {
                throw new JSONException("Missing plant schedule.");
            }
            for (int index = 0; index < plants.length(); index++) {
                JSONObject plant = plants.optJSONObject(index);
                if (plant == null
                        || plant.optString("name", "").isEmpty()
                        || !plant.optString("dueDate", "").matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw new JSONException("Invalid plant schedule.");
                }
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_PLAN, plan.toString())
                    .apply();
            scheduleNext(context);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Invalid watering plan.", exception);
        }
    }

    static void scheduleNext(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            throw new IllegalStateException("Android alarm service is unavailable.");
        }
        PendingIntent pendingIntent = reminderPendingIntent(context);
        JSONObject plan;
        try {
            String saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_PLAN, null);
            plan = saved == null ? null : new JSONObject(saved);
        } catch (JSONException exception) {
            throw new IllegalStateException("Saved watering plan is invalid.", exception);
        }
        if (plan == null || plan.optJSONArray("plants") == null || plan.optJSONArray("plants").length() == 0) {
            alarmManager.cancel(pendingIntent);
            return;
        }
        String[] time = plan.optString("time", "09:00").split(":");
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, Integer.parseInt(time[0]));
        next.set(Calendar.MINUTE, Integer.parseInt(time[1]));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.getTime().after(new Date())) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pendingIntent);
    }

    private static PendingIntent reminderPendingIntent(Context context) {
        Intent intent = new Intent(context, WateringPlanReceiver.class).setAction(ACTION_REMINDER);
        return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            scheduleNext(context);
            return;
        }
        if (!ACTION_REMINDER.equals(intent.getAction())) {
            return;
        }
        try {
            notifyIfDue(context);
        } finally {
            scheduleNext(context);
        }
    }

    private void notifyIfDue(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled())) {
            return;
        }
        JSONObject plan;
        try {
            String saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(PREF_PLAN, null);
            plan = saved == null ? null : new JSONObject(saved);
        } catch (JSONException exception) {
            throw new IllegalStateException("Saved watering plan is invalid.", exception);
        }
        if (plan == null) return;

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (today.equals(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_LAST_NOTIFIED_DATE, ""))) {
            return;
        }
        JSONArray plants = plan.optJSONArray("plants");
        if (plants == null) return;
        ArrayList<String> names = new ArrayList<>();
        for (int index = 0; index < plants.length(); index++) {
            JSONObject plant = plants.optJSONObject(index);
            if (plant != null && plant.optString("dueDate", "9999-99-99").compareTo(today) <= 0) {
                names.add(plant.optString("name", "植物"));
            }
        }
        if (names.isEmpty()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(context.getString(R.string.notification_channel_description));
            manager.createNotificationChannel(channel);
        }
        StringBuilder messageBuilder = new StringBuilder();
        for (int index = 0; index < Math.min(names.size(), 4); index++) {
            if (index > 0) messageBuilder.append("、");
            messageBuilder.append(names.get(index));
        }
        String message = messageBuilder.toString();
        if (names.size() > 4) message += " ほか" + (names.size() - 4) + "鉢";
        String body = message + "の土が乾いているか確認しましょう。湿っている時は水やりを待ちます。";
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent contentIntent = launchIntent == null ? null : PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("植物の水やり目安です 🌿")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        manager.notify(("watering-" + today).hashCode(), notification);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_NOTIFIED_DATE, today)
                .apply();
    }
}
