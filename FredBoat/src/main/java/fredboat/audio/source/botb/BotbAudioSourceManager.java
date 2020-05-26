package fredboat.audio.source.botb;

import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.*;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.*;

/**
 * Audio source manager that implements finding SoundCloud tracks based on URL.
 */
public class BotbAudioSourceManager implements AudioSourceManager, HttpConfigurable {
    private static final Logger log = LoggerFactory.getLogger(BotbAudioSourceManager.class);

    private static final String TRACK_URL_REGEX = "^(?:http://|https://|)(?:www\\.|)battleofthebits.org/arena/Entry/[^\\/]+/";
    private static final String PLAYER_URL_REGEX = "^(?:http://|https://|)(?:www\\.|)battleofthebits.org/player/EntryPlay/";

    private static final Pattern trackUrlPattern = Pattern.compile(TRACK_URL_REGEX);
    private static final Pattern playerUrlPattern = Pattern.compile(PLAYER_URL_REGEX);

    private final BotbJsonDataLoader htmlDataLoader;
    private final HttpInterfaceManager httpInterfaceManager;

    private final HttpAudioSourceManager httpAudioSourceManager;

    /**
     * Create an instance.
     */
    public BotbAudioSourceManager(
            BotbJsonDataLoader htmlDataLoader,
            HttpAudioSourceManager httpAudioSourceManager) {
        this.htmlDataLoader = htmlDataLoader;
        this.httpAudioSourceManager = httpAudioSourceManager;

        httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    }

    @Override
    public String getSourceName() {
        return "botb";
    }

    @Override
    public AudioItem loadItem(DefaultAudioPlayerManager manager, AudioReference reference) {
        //change this later to reimplement whatever the http thing does so I can attach author myself

        String identifier = reference.identifier;

        AudioReference newReference;

        if (identifier.startsWith("botb ")){
            newReference = processSearch(identifier.replaceFirst("botb ", ""));
        } else {
            newReference = processAsSingleTrack(identifier);
        }

        if (newReference == null) {
            return null;
        }
        return httpAudioSourceManager.loadItem(manager, newReference);
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
        // No extra information to save
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        return httpAudioSourceManager.decodeTrack(trackInfo, input);
    }

    @Override
    public void shutdown() {
        // Nothing to shut down
    }

    /**
     * @return Get an HTTP interface for a playing track.
     */
    public HttpInterface getHttpInterface() {
        return httpInterfaceManager.getInterface();
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        httpInterfaceManager.configureRequests(configurator);
    }

    @Override
    public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
        httpInterfaceManager.configureBuilder(configurator);
    }

    private AudioReference processSearch(String name) {
        try (HttpInterface httpInterface = getHttpInterface()) {
            String searchUrl = UriComponentsBuilder
                    .fromHttpUrl("https://battleofthebits.org/api/v1/entry/search/{query}")
                    .buildAndExpand(UriUtils.encode(name, "UTF-8") + ".json")
                    .toUriString();

            log.info("SEARCHING URL "+searchUrl);

            JsonBrowser trackData = htmlDataLoader.load(httpInterface, searchUrl);

            if (trackData == null) {
                throw new FriendlyException("This track is not available", COMMON, null);
            }

            return loadFromTrackData(trackData.index(0));
        } catch (UnsupportedEncodingException e) {
            throw new FriendlyException("^^ this code is wrong please look at it", FAULT, e);
        } catch (IOException e) {
            throw new FriendlyException("Search failed to find any tracks.", COMMON, e);
        }
    }

    private AudioReference processAsSingleTrack(String url) {
        Matcher trackUrlMatcher = trackUrlPattern.matcher(url);
        if (trackUrlMatcher.lookingAt()) {
            return loadTrack(trackUrlMatcher.replaceFirst(""));
        }

        Matcher playerUrlMatcher = playerUrlPattern.matcher(url);
        if (playerUrlMatcher.lookingAt()) {
            return loadTrack(playerUrlMatcher.replaceFirst(""));
        }

        return null;
    }

    public AudioReference loadTrack(String trackId) {
        String trackInfoUrl = "https://battleofthebits.org/api/v1/entry/load/" + trackId;

        try (HttpInterface httpInterface = getHttpInterface()) {
            JsonBrowser trackData = htmlDataLoader.load(httpInterface, trackInfoUrl);

            if (trackData == null) {
                throw new FriendlyException("This track is not available", COMMON, null);
            }

            return loadFromTrackData(trackData);
        } catch (IOException e) {
            throw new FriendlyException("Loading track from botb failed.", SUSPICIOUS, e);
        }
    }

    protected AudioReference loadFromTrackData(JsonBrowser trackData) {
        String title = trackData.get("title").text();
        String playUrl = trackData.get("play_url").text();

        AudioReference reference = new AudioReference(playUrl, title);

        return reference;
//        AudioTrackInfo info = AudioTrackInfoBuilder.create(reference, null)
//                .setAuthor(trackData.get("botbr").get("name").text())
//                .build();
//        httpAudioSourceManager.loadItem(null, reference);
//        return buildTrackFromInfo(info);
    }

//    private AudioTrack buildTrackFromInfo(AudioTrackInfo trackInfo) {
//        return new BotbAudioTrack(trackInfo, this);
//    }
}
