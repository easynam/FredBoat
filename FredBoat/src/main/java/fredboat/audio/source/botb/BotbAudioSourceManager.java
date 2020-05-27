package fredboat.audio.source.botb;

import com.sedmelluq.discord.lavaplayer.container.*;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.ProbingAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.*;
import com.sedmelluq.discord.lavaplayer.track.*;
import com.sedmelluq.discord.lavaplayer.track.info.AudioTrackInfoBuilder;
import org.apache.http.HttpStatus;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult.refer;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.*;
import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.getHeaderValue;

/**
 * Audio source manager that implements finding SoundCloud tracks based on URL.
 */
public class BotbAudioSourceManager extends ProbingAudioSourceManager implements HttpConfigurable {
    private static final Logger log = LoggerFactory.getLogger(BotbAudioSourceManager.class);

    private static final String TRACK_URL_REGEX = "^(?:http://|https://|)(?:www\\.|)battleofthebits.org/arena/Entry/[^\\/]+/";
    private static final String PLAYER_URL_REGEX = "^(?:http://|https://|)(?:www\\.|)battleofthebits.org/player/EntryPlay/";

    private static final Pattern trackUrlPattern = Pattern.compile(TRACK_URL_REGEX);
    private static final Pattern playerUrlPattern = Pattern.compile(PLAYER_URL_REGEX);

    private final BotbJsonDataLoader htmlDataLoader;
    private final HttpInterfaceManager httpInterfaceManager;

    /**
     * Create an instance.
     */
    public BotbAudioSourceManager(
            BotbJsonDataLoader htmlDataLoader,
            MediaContainerRegistry containerRegistry) {
        super(containerRegistry);

        this.htmlDataLoader = htmlDataLoader;
        httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
    }

    @Override
    public String getSourceName() {
        return "http";
    }

    @Override
    public AudioItem loadItem(DefaultAudioPlayerManager manager, AudioReference reference) {
        //change this later to reimplement whatever the http thing does so I can attach author myself
        String identifier = reference.identifier;

        AudioTrackInfo trackInfo;

        if (identifier.startsWith("botb ")){
            trackInfo = processSearch(identifier.replaceFirst("botb ", ""));
        } else {
            trackInfo = processAsSingleTrack(identifier);
        }

        if (trackInfo == null) {
            return null;
        }

        MediaContainerDetectionResult container = detectContainer(trackInfo);

        return createTrack(
                AudioTrackInfoBuilder.create(new AudioReference(trackInfo.identifier, trackInfo.title), null)
                        .setAuthor(trackInfo.author)
                        .setLength(container.getTrackInfo().length)
                        .build(),
                container.getContainerDescriptor());
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) throws IOException {
        encodeTrackFactory(((BotbAudioTrack) track).getContainerTrackFactory(), output);
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
        MediaContainerDescriptor containerTrackFactory = decodeTrackFactory(input);

        if (containerTrackFactory != null) {
            return new BotbAudioTrack(trackInfo, containerTrackFactory, this);
        }

        return null;
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

    @Override
    protected AudioTrack createTrack(AudioTrackInfo trackInfo, MediaContainerDescriptor containerDescriptor) {
        return new BotbAudioTrack(trackInfo, containerDescriptor, this);
    }

    private AudioTrackInfo processSearch(String name) {
        try (HttpInterface httpInterface = getHttpInterface()) {
            String searchUrl = UriComponentsBuilder
                    .fromHttpUrl("https://battleofthebits.org/api/v1/entry/search/{query}")
                    .buildAndExpand(UriUtils.encode(name, "UTF-8") + ".json")
                    .toUriString();

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

    private AudioTrackInfo processAsSingleTrack(String url) {
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

    public AudioTrackInfo loadTrack(String trackId) {
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

    protected AudioTrackInfo loadFromTrackData(JsonBrowser trackData) {
        String title = trackData.get("title").text();
        String playUrl = trackData.get("play_url").text();

        AudioReference reference = new AudioReference(playUrl, title);

        return AudioTrackInfoBuilder.create(reference, null)
                .setAuthor(trackData.get("botbr").get("name").text())
                .build();
    }

    private MediaContainerDetectionResult detectContainer(AudioTrackInfo trackInfo) {
        MediaContainerDetectionResult result;

        try (HttpInterface httpInterface = getHttpInterface()) {
            result = detectContainerWithClient(httpInterface, new AudioReference(trackInfo.identifier, trackInfo.title));
        } catch (IOException e) {
            throw new FriendlyException("Connecting to the URL failed.", SUSPICIOUS, e);
        }

        return result;
    }

    private MediaContainerDetectionResult detectContainerWithClient(HttpInterface httpInterface, AudioReference reference) throws IOException {
        try (PersistentHttpStream inputStream = new PersistentHttpStream(httpInterface, new URI(reference.identifier), Long.MAX_VALUE)) {
            int statusCode = inputStream.checkStatusCode();
            String redirectUrl = HttpClientTools.getRedirectLocation(reference.identifier, inputStream.getCurrentResponse());

            if (redirectUrl != null) {
                return refer(null, new AudioReference(redirectUrl, null));
            } else if (statusCode == HttpStatus.SC_NOT_FOUND) {
                return null;
            } else if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                throw new FriendlyException("That URL is not playable.", COMMON, new IllegalStateException("Status code " + statusCode));
            }

            MediaContainerHints hints = MediaContainerHints.from(getHeaderValue(inputStream.getCurrentResponse(), "Content-Type"), null);
            return new MediaContainerDetection(containerRegistry, reference, inputStream, hints).detectContainer();
        } catch (URISyntaxException e) {
            throw new FriendlyException("Not a valid URL.", COMMON, e);
        }
    }

}
