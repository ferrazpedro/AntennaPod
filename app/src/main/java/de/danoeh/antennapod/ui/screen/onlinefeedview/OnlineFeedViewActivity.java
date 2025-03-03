package de.danoeh.antennapod.ui.screen.onlinefeedview;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.LightingColorFilter;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.snackbar.Snackbar;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.net.download.service.feed.remote.Downloader;
import de.danoeh.antennapod.net.download.service.feed.remote.HttpDownloader;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.common.ThemeSwitcher;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadRequestCreator;
import de.danoeh.antennapod.net.discovery.FeedUrlNotFoundException;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import de.danoeh.antennapod.playback.service.PlaybackServiceInterface;
import de.danoeh.antennapod.ui.screen.download.DownloadErrorLabel;
import de.danoeh.antennapod.databinding.EditTextDialogBinding;
import de.danoeh.antennapod.databinding.OnlinefeedviewHeaderBinding;
import de.danoeh.antennapod.event.EpisodeDownloadEvent;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.net.download.serviceinterface.DownloadServiceInterface;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.model.download.DownloadRequest;
import de.danoeh.antennapod.model.download.DownloadResult;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.net.discovery.CombinedSearcher;
import de.danoeh.antennapod.net.discovery.PodcastSearchResult;
import de.danoeh.antennapod.net.discovery.PodcastSearcherRegistry;
import de.danoeh.antennapod.parser.feed.FeedHandler;
import de.danoeh.antennapod.parser.feed.FeedHandlerResult;
import de.danoeh.antennapod.model.download.DownloadError;
import de.danoeh.antennapod.ui.common.IntentUtils;
import de.danoeh.antennapod.net.common.UrlChecker;
import de.danoeh.antennapod.ui.cleaner.HtmlToPlainText;
import de.danoeh.antennapod.databinding.OnlinefeedviewActivityBinding;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.playback.RemoteMedia;
import de.danoeh.antennapod.parser.feed.UnsupportedFeedtypeException;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import de.danoeh.antennapod.ui.glide.FastBlurTransformation;
import de.danoeh.antennapod.ui.preferences.screen.synchronization.AuthenticationDialog;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.observers.DisposableMaybeObserver;
import io.reactivex.schedulers.Schedulers;
import org.apache.commons.lang3.StringUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static de.danoeh.antennapod.ui.appstartintent.OnlineFeedviewActivityStarter.ARG_FEEDURL;
import static de.danoeh.antennapod.ui.appstartintent.OnlineFeedviewActivityStarter.ARG_STARTED_FROM_SEARCH;
import static de.danoeh.antennapod.ui.appstartintent.OnlineFeedviewActivityStarter.ARG_WAS_MANUAL_URL;

/**
 * Downloads a feed from a feed URL and parses it. Subclasses can display the
 * feed object that was parsed. This activity MUST be started with a given URL
 * or an Exception will be thrown.
 * <p/>
 * If the feed cannot be downloaded or parsed, an error dialog will be displayed
 * and the activity will finish as soon as the error dialog is closed.
 */
public class OnlineFeedViewActivity extends AppCompatActivity {

    private static final int RESULT_ERROR = 2;
    private static final String TAG = "OnlineFeedViewActivity";
    private static final String PREFS = "OnlineFeedViewActivityPreferences";
    private static final String PREF_LAST_AUTO_DOWNLOAD = "lastAutoDownload";
    private static final int DESCRIPTION_MAX_LINES_COLLAPSED = 4;

    private volatile List<Feed> feeds;
    private String selectedDownloadUrl;
    private Downloader downloader;
    private String username = null;
    private String password = null;

    private boolean isPaused;
    private boolean didPressSubscribe = false;
    private boolean isFeedFoundBySearch = false;

    private Dialog dialog;

    private Disposable download;
    private Disposable parser;
    private Disposable updater;

    private OnlinefeedviewHeaderBinding headerBinding;
    private OnlinefeedviewActivityBinding viewBinding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(ThemeSwitcher.getTranslucentTheme(this));
        super.onCreate(savedInstanceState);

        viewBinding = OnlinefeedviewActivityBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        viewBinding.transparentBackground.setOnClickListener(v -> finish());
        viewBinding.closeButton.setOnClickListener(view -> finish());
        viewBinding.card.setOnClickListener(null);
        viewBinding.card.setCardBackgroundColor(ThemeUtils.getColorFromAttr(this, R.attr.colorSurface));
        headerBinding = OnlinefeedviewHeaderBinding.inflate(getLayoutInflater());

        String feedUrl = null;
        if (getIntent().hasExtra(ARG_FEEDURL)) {
            feedUrl = getIntent().getStringExtra(ARG_FEEDURL);
        } else if (TextUtils.equals(getIntent().getAction(), Intent.ACTION_SEND)) {
            feedUrl = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        } else if (TextUtils.equals(getIntent().getAction(), Intent.ACTION_VIEW)) {
            feedUrl = getIntent().getDataString();
        }

        if (feedUrl == null) {
            Log.e(TAG, "feedUrl is null.");
            showNoPodcastFoundError();
        } else {
            Log.d(TAG, "Activity was started with url " + feedUrl);
            setLoadingLayout();
            // Remove subscribeonandroid.com from feed URL in order to subscribe to the actual feed URL
            if (feedUrl.contains("subscribeonandroid.com")) {
                feedUrl = feedUrl.replaceFirst("((www.)?(subscribeonandroid.com/))", "");
            }
            if (savedInstanceState != null) {
                username = savedInstanceState.getString("username");
                password = savedInstanceState.getString("password");
            }
            lookupUrlAndDownload(feedUrl);
        }
    }

    private void showNoPodcastFoundError() {
        runOnUiThread(() -> new MaterialAlertDialogBuilder(OnlineFeedViewActivity.this)
                .setNeutralButton(android.R.string.ok, (dialog, which) -> finish())
                .setTitle(R.string.error_label)
                .setMessage(R.string.null_value_podcast_error)
                .setOnDismissListener(dialog1 -> {
                    setResult(RESULT_ERROR);
                    finish();
                })
                .show());
    }

    /**
     * Displays a progress indicator.
     */
    private void setLoadingLayout() {
        viewBinding.progressBar.setVisibility(View.VISIBLE);
        viewBinding.feedDisplayContainer.setVisibility(View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        isPaused = false;
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        isPaused = true;
        EventBus.getDefault().unregister(this);
        if (downloader != null && !downloader.isFinished()) {
            downloader.cancel();
        }
        if(dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(updater != null) {
            updater.dispose();
        }
        if(download != null) {
            download.dispose();
        }
        if(parser != null) {
            parser.dispose();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("username", username);
        outState.putString("password", password);
    }

    private void resetIntent(String url) {
        Intent intent = new Intent();
        intent.putExtra(ARG_FEEDURL, url);
        setIntent(intent);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void lookupUrlAndDownload(String url) {
        download = PodcastSearcherRegistry.lookupUrl(url)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(this::startFeedDownload,
                        error -> {
                            if (error instanceof FeedUrlNotFoundException) {
                                tryToRetrieveFeedUrlBySearch((FeedUrlNotFoundException) error);
                            } else {
                                showNoPodcastFoundError();
                                Log.e(TAG, Log.getStackTraceString(error));
                            }
                        });
    }

    private void tryToRetrieveFeedUrlBySearch(FeedUrlNotFoundException error) {
        Log.d(TAG, "Unable to retrieve feed url, trying to retrieve feed url from search");
        String url = searchFeedUrlByTrackName(error.getTrackName(), error.getArtistName());
        if (url != null) {
            Log.d(TAG, "Successfully retrieve feed url");
            isFeedFoundBySearch = true;
            startFeedDownload(url);
        } else {
            showNoPodcastFoundError();
            Log.d(TAG, "Failed to retrieve feed url");
        }
    }

    private String searchFeedUrlByTrackName(String trackName, String artistName) {
        CombinedSearcher searcher = new CombinedSearcher();
        String query = trackName + " " + artistName;
        List<PodcastSearchResult> results = searcher.search(query).blockingGet();
        for (PodcastSearchResult result : results) {
            if (result.feedUrl != null && result.author != null
                    && result.author.equalsIgnoreCase(artistName) && result.title.equalsIgnoreCase(trackName)) {
                return result.feedUrl;

            }
        }
        return null;
    }

    private void startFeedDownload(String url) {
        Log.d(TAG, "Starting feed download");
        selectedDownloadUrl = UrlChecker.prepareUrl(url);
        DownloadRequest request = DownloadRequestCreator.create(new Feed(selectedDownloadUrl, null))
                .withAuthentication(username, password)
                .withInitiatedByUser(true)
                .build();

        download = Observable.fromCallable(() -> {
            feeds = DBReader.getFeedList();
            downloader = new HttpDownloader(request);
            downloader.call();
            return downloader.getResult();
        })
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(status -> checkDownloadResult(status, request.getDestination()),
                error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void checkDownloadResult(@NonNull DownloadResult status, String destination) {
        if (status.isSuccessful()) {
            parseFeed(destination);
        } else if (status.getReason() == DownloadError.ERROR_UNAUTHORIZED) {
            if (!isFinishing() && !isPaused) {
                if (username != null && password != null) {
                    Toast.makeText(this, R.string.download_error_unauthorized, Toast.LENGTH_LONG).show();
                }
                dialog = new FeedViewAuthenticationDialog(OnlineFeedViewActivity.this,
                        R.string.authentication_notification_title,
                        downloader.getDownloadRequest().getSource()).create();
                dialog.show();
            }
        } else {
            showErrorDialog(getString(DownloadErrorLabel.from(status.getReason())), status.getReasonDetailed());
        }
    }

    @Subscribe
    public void onFeedListChanged(FeedListUpdateEvent event) {
        updater = Observable.fromCallable(DBReader::getFeedList)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        feeds -> {
                            OnlineFeedViewActivity.this.feeds = feeds;
                            handleUpdatedFeedStatus();
                        }, error -> Log.e(TAG, Log.getStackTraceString(error))
                );
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEventMainThread(EpisodeDownloadEvent event) {
        handleUpdatedFeedStatus();
    }

    private void parseFeed(String destination) {
        Log.d(TAG, "Parsing feed");
        parser = Maybe.fromCallable(() -> doParseFeed(destination))
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeWith(new DisposableMaybeObserver<FeedHandlerResult>() {
                    @Override
                    public void onSuccess(@NonNull FeedHandlerResult result) {
                        showFeedInformation(result.feed, result.alternateFeedUrls);
                    }

                    @Override
                    public void onComplete() {
                        // Ignore null result: We showed the discovery dialog.
                    }

                    @Override
                    public void onError(@NonNull Throwable error) {
                        showErrorDialog(error.getMessage(), "");
                        Log.d(TAG, "Feed parser exception: " + Log.getStackTraceString(error));
                    }
                });
    }

    /**
     * Try to parse the feed.
     * @return  The FeedHandlerResult if successful.
     *          Null if unsuccessful but we started another attempt.
     * @throws Exception If unsuccessful but we do not know a resolution.
     */
    @Nullable
    private FeedHandlerResult doParseFeed(String destination) throws Exception {
        FeedHandler handler = new FeedHandler();
        Feed feed = new Feed(selectedDownloadUrl, null);
        feed.setLocalFileUrl(destination);
        File destinationFile = new File(destination);
        try {
            return handler.parseFeed(feed);
        } catch (UnsupportedFeedtypeException e) {
            Log.d(TAG, "Unsupported feed type detected");
            if ("html".equalsIgnoreCase(e.getRootElement())) {
                boolean dialogShown = showFeedDiscoveryDialog(destinationFile, selectedDownloadUrl);
                if (dialogShown) {
                    return null; // Should not display an error message
                } else {
                    throw new UnsupportedFeedtypeException(getString(R.string.download_error_unsupported_type_html));
                }
            } else {
                throw e;
            }
        } catch (Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
            throw e;
        } finally {
            boolean rc = destinationFile.delete();
            Log.d(TAG, "Deleted feed source file. Result: " + rc);
        }
    }

    /**
     * Called when feed parsed successfully.
     * This method is executed on the GUI thread.
     */
    private void showFeedInformation(final Feed feed, Map<String, String> alternateFeedUrls) {
        viewBinding.progressBar.setVisibility(View.GONE);
        viewBinding.feedDisplayContainer.setVisibility(View.VISIBLE);
        if (isFeedFoundBySearch) {
            int resId = R.string.no_feed_url_podcast_found_by_search;
            Snackbar.make(findViewById(android.R.id.content), resId, Snackbar.LENGTH_LONG).show();
        }

        viewBinding.backgroundImage.setColorFilter(new LightingColorFilter(0xff828282, 0x000000));

        viewBinding.listView.addHeaderView(headerBinding.getRoot());
        viewBinding.listView.setSelector(android.R.color.transparent);
        viewBinding.listView.setAdapter(new FeedItemlistDescriptionAdapter(this, 0, feed.getItems()));

        if (StringUtils.isNotBlank(feed.getImageUrl())) {
            Glide.with(this)
                    .load(feed.getImageUrl())
                    .apply(new RequestOptions()
                        .placeholder(R.color.light_gray)
                        .error(R.color.light_gray)
                        .fitCenter()
                        .dontAnimate())
                    .into(viewBinding.coverImage);
            Glide.with(this)
                    .load(feed.getImageUrl())
                    .apply(new RequestOptions()
                            .placeholder(R.color.image_readability_tint)
                            .error(R.color.image_readability_tint)
                            .transform(new FastBlurTransformation())
                            .dontAnimate())
                    .into(viewBinding.backgroundImage);
        }

        viewBinding.titleLabel.setText(feed.getTitle());
        viewBinding.authorLabel.setText(feed.getAuthor());
        headerBinding.txtvDescription.setText(HtmlToPlainText.getPlainText(feed.getDescription()));

        viewBinding.subscribeButton.setOnClickListener(v -> {
            if (feedInFeedlist()) {
                openFeed();
            } else {
                FeedDatabaseWriter.updateFeed(this, feed, false);
                didPressSubscribe = true;
                handleUpdatedFeedStatus();
            }
        });

        viewBinding.stopPreviewButton.setOnClickListener(v -> {
            PlaybackPreferences.writeNoMediaPlaying();
            IntentUtils.sendLocalBroadcast(this, PlaybackServiceInterface.ACTION_SHUTDOWN_PLAYBACK_SERVICE);
        });

        if (UserPreferences.isEnableAutodownload()) {
            SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
            viewBinding.autoDownloadCheckBox.setChecked(preferences.getBoolean(PREF_LAST_AUTO_DOWNLOAD, true));
        }

        headerBinding.txtvDescription.setMaxLines(DESCRIPTION_MAX_LINES_COLLAPSED);
        headerBinding.txtvDescription.setOnClickListener(v -> {
            if (headerBinding.txtvDescription.getMaxLines() > DESCRIPTION_MAX_LINES_COLLAPSED) {
                headerBinding.txtvDescription.setMaxLines(DESCRIPTION_MAX_LINES_COLLAPSED);
            } else {
                headerBinding.txtvDescription.setMaxLines(2000);
            }
        });

        if (alternateFeedUrls.isEmpty()) {
            viewBinding.alternateUrlsSpinner.setVisibility(View.GONE);
        } else {
            viewBinding.alternateUrlsSpinner.setVisibility(View.VISIBLE);

            final List<String> alternateUrlsList = new ArrayList<>();
            final List<String> alternateUrlsTitleList = new ArrayList<>();

            alternateUrlsList.add(feed.getDownloadUrl());
            alternateUrlsTitleList.add(feed.getTitle());


            alternateUrlsList.addAll(alternateFeedUrls.keySet());
            for (String url : alternateFeedUrls.keySet()) {
                alternateUrlsTitleList.add(alternateFeedUrls.get(url));
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                    R.layout.alternate_urls_item, alternateUrlsTitleList) {
                @Override
                public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    // reusing the old view causes a visual bug on Android <= 10
                    return super.getDropDownView(position, null, parent);
                }
            };

            adapter.setDropDownViewResource(R.layout.alternate_urls_dropdown_item);
            viewBinding.alternateUrlsSpinner.setAdapter(adapter);
            viewBinding.alternateUrlsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    selectedDownloadUrl = alternateUrlsList.get(position);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });
        }
        handleUpdatedFeedStatus();
    }

    private void openFeed() {
        // feed.getId() is always 0, we have to retrieve the id from the feed list from the database
        MainActivityStarter mainActivityStarter = new MainActivityStarter(this);
        mainActivityStarter.withOpenFeed(getFeedId());
        if (getIntent().getBooleanExtra(ARG_STARTED_FROM_SEARCH, false)) {
            mainActivityStarter.withAddToBackStack();
        }
        finish();
        startActivity(mainActivityStarter.getIntent());
    }

    private void handleUpdatedFeedStatus() {
        if (DownloadServiceInterface.get().isDownloadingEpisode(selectedDownloadUrl)) {
            viewBinding.subscribeButton.setEnabled(false);
            viewBinding.subscribeButton.setText(R.string.subscribing_label);
        } else if (feedInFeedlist()) {
            viewBinding.subscribeButton.setEnabled(true);
            viewBinding.subscribeButton.setText(R.string.open_podcast);
            if (didPressSubscribe) {
                didPressSubscribe = false;

                Feed feed1 = DBReader.getFeed(getFeedId());
                FeedPreferences feedPreferences = feed1.getPreferences();
                if (UserPreferences.isEnableAutodownload()) {
                    boolean autoDownload = viewBinding.autoDownloadCheckBox.isChecked();
                    feedPreferences.setAutoDownload(autoDownload);

                    SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putBoolean(PREF_LAST_AUTO_DOWNLOAD, autoDownload);
                    editor.apply();
                }
                if (username != null) {
                    feedPreferences.setUsername(username);
                    feedPreferences.setPassword(password);
                }
                DBWriter.setFeedPreferences(feedPreferences);

                openFeed();
            }
        } else {
            viewBinding.subscribeButton.setEnabled(true);
            viewBinding.subscribeButton.setText(R.string.subscribe_label);
            if (UserPreferences.isEnableAutodownload()) {
                viewBinding.autoDownloadCheckBox.setVisibility(View.VISIBLE);
            }
        }
    }

    private boolean feedInFeedlist() {
        return getFeedId() != 0;
    }

    private long getFeedId() {
        if (feeds == null) {
            return 0;
        }
        for (Feed f : feeds) {
            if (f.getDownloadUrl().equals(selectedDownloadUrl)) {
                return f.getId();
            }
        }
        return 0;
    }

    @UiThread
    private void showErrorDialog(String errorMsg, String details) {
        if (!isFinishing() && !isPaused) {
            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle(R.string.error_label);
            if (errorMsg != null) {
                String total = errorMsg + "\n\n" + details;
                SpannableString errorMessage = new SpannableString(total);
                errorMessage.setSpan(new ForegroundColorSpan(0x88888888),
                        errorMsg.length(), total.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setMessage(errorMessage);
            } else {
                builder.setMessage(R.string.download_error_error_unknown);
            }
            builder.setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.cancel());
            if (getIntent().getBooleanExtra(ARG_WAS_MANUAL_URL, false)) {
                builder.setNeutralButton(R.string.edit_url_menu, (dialog, which) -> editUrl());
            }
            builder.setOnCancelListener(dialog -> {
                setResult(RESULT_ERROR);
                finish();
            });
            if (dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
            dialog = builder.show();
        }
    }

    private void editUrl() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(R.string.edit_url_menu);
        final EditTextDialogBinding dialogBinding = EditTextDialogBinding.inflate(getLayoutInflater());
        if (downloader != null) {
            dialogBinding.urlEditText.setText(downloader.getDownloadRequest().getSource());
        }
        builder.setView(dialogBinding.getRoot());
        builder.setPositiveButton(R.string.confirm_label, (dialog, which) -> {
            setLoadingLayout();
            lookupUrlAndDownload(dialogBinding.urlEditText.getText().toString());
        });
        builder.setNegativeButton(R.string.cancel_label, (dialog1, which) -> dialog1.cancel());
        builder.setOnCancelListener(dialog1 -> {
            setResult(RESULT_ERROR);
            finish();
        });
        builder.show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void playbackStateChanged(PlayerStatusEvent event) {
        boolean isPlayingPreview =
                PlaybackPreferences.getCurrentlyPlayingMediaType() == RemoteMedia.PLAYABLE_TYPE_REMOTE_MEDIA;
        viewBinding.stopPreviewButton.setVisibility(isPlayingPreview ? View.VISIBLE : View.GONE);
    }

    /**
     *
     * @return true if a FeedDiscoveryDialog is shown, false otherwise (e.g., due to no feed found).
     */
    private boolean showFeedDiscoveryDialog(File feedFile, String baseUrl) {
        FeedDiscoverer fd = new FeedDiscoverer();
        final Map<String, String> urlsMap;
        try {
            urlsMap = fd.findLinks(feedFile, baseUrl);
            if (urlsMap == null || urlsMap.isEmpty()) {
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        if (isPaused || isFinishing()) {
            return false;
        }

        final List<String> titles = new ArrayList<>();

        final List<String> urls = new ArrayList<>(urlsMap.keySet());
        for (String url : urls) {
            titles.add(urlsMap.get(url));
        }

        if (urls.size() == 1) {
            // Skip dialog and display the item directly
            resetIntent(urls.get(0));
            startFeedDownload(urls.get(0));
            return true;
        }

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(OnlineFeedViewActivity.this,
                R.layout.ellipsize_start_listitem, R.id.txtvTitle, titles);
        DialogInterface.OnClickListener onClickListener = (dialog, which) -> {
            String selectedUrl = urls.get(which);
            dialog.dismiss();
            resetIntent(selectedUrl);
            startFeedDownload(selectedUrl);
        };

        MaterialAlertDialogBuilder ab = new MaterialAlertDialogBuilder(OnlineFeedViewActivity.this)
                .setTitle(R.string.feeds_label)
                .setCancelable(true)
                .setOnCancelListener(dialog -> finish())
                .setAdapter(adapter, onClickListener);

        runOnUiThread(() -> {
            if(dialog != null && dialog.isShowing()) {
                dialog.dismiss();
            }
            dialog = ab.show();
        });
        return true;
    }

    private class FeedViewAuthenticationDialog extends AuthenticationDialog {

        private final String feedUrl;

        FeedViewAuthenticationDialog(Context context, int titleRes, String feedUrl) {
            super(context, titleRes, true, username, password);
            this.feedUrl = feedUrl;
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
            finish();
        }

        @Override
        protected void onConfirmed(String username, String password) {
            OnlineFeedViewActivity.this.username = username;
            OnlineFeedViewActivity.this.password = password;
            startFeedDownload(feedUrl);
        }
    }

}
