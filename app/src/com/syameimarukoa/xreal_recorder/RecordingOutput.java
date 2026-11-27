package com.syameimarukoa.xreal_recorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Creates a user-visible MP4 without requiring direct filesystem access. */
final class RecordingOutput {
    private final Context context;
    private final ContentResolver resolver;
    private final Uri uri;
    private final boolean pendingMedia;
    private final String filename;
    private final Uri tree;
    private boolean completed;

    private RecordingOutput(Context context, ContentResolver resolver, Uri uri,
                            boolean pendingMedia, String filename, Uri tree) {
        this.context = context;
        this.resolver = resolver;
        this.uri = uri;
        this.pendingMedia = pendingMedia;
        this.filename = filename;
        this.tree = tree;
    }

    static RecordingOutput create(Context context, Uri tree, long captureTimeMs) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri uri;
        boolean pending = tree == null;
        String extension = "mp4";
        String mimeType = "video/mp4";
        String filename = nextFilename(resolver, tree, extension, captureTimeMs);
        if (tree != null) {
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            uri = DocumentsContract.createDocument(resolver, directory, mimeType, filename);
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Video.Media.MIME_TYPE, mimeType);
            // MediaStore.Video only allows DCIM/Movies/Pictures; Documents is
            // rejected. Keep the MP4 in DCIM for Gallery/Photos.
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_DCIM + "/100XREAL");
            values.put(MediaStore.Video.Media.DATE_TAKEN, captureTimeMs);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        }
        if (uri == null) {
            throw new IOException("Could not create MP4 in the selected location");
        }
        String actualName = filename;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                actualName = cursor.getString(0);
            }
        }
        return new RecordingOutput(context, resolver, uri, pending, actualName, tree);
    }

    private static String nextFilename(ContentResolver resolver, Uri tree,
                                       String extension, long captureTimeMs) throws IOException {
        String base = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date(captureTimeMs));
        Uri children;
        String selection = null;
        String[] selectionArgs = null;
        if (tree != null) {
            children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
        } else {
            children = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            selection = MediaStore.Video.Media.RELATIVE_PATH + "=?";
            selectionArgs = new String[]{Environment.DIRECTORY_DCIM + "/100XREAL/"};
        }
        ensureNameAvailable(resolver, children, selection, selectionArgs, base);
        if (tree == null) {
            ensureNameAvailable(resolver, MediaStore.Files.getContentUri("external"),
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{Environment.DIRECTORY_DOCUMENTS + "/XREAL/100XREAL/"},
                    base);
        }
        return base + "." + extension;
    }

    private static void ensureNameAvailable(ContentResolver resolver, Uri children, String selection,
                                            String[] selectionArgs, String base)
            throws IOException {
        try (Cursor cursor = resolver.query(children,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, selection, selectionArgs, null)) {
            if (cursor == null) {
                throw new IOException("Could not list recordings in the selected location");
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                if (name != null && (name.equalsIgnoreCase(base + ".mp4")
                        || name.equalsIgnoreCase(base + ".json")
                        || name.equalsIgnoreCase(base + ".gcsv"))) {
                    throw new IOException("Recording name already exists: " + base);
                }
            }
        }
    }

    MuxerSink openMuxer() throws IOException {
        ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "rw");
        if (fd == null) {
            throw new IOException("Could not open MP4 in the selected location");
        }
        try {
            return new MuxerSink(fd.getFileDescriptor());
        } finally {
            fd.close();
        }
    }

    /** Open a single-write fMP4 sink with an embedded Gyroflow metadata track. */
    StreamingMuxerSink openStreamingMuxer(byte[] csd, int width, int height)
            throws IOException {
        java.io.OutputStream stream = resolver.openOutputStream(uri, "rw");
        if (stream == null) {
            throw new IOException("Could not open fMP4 in the selected location");
        }
        StreamingGyroflowMuxer muxer =
                new StreamingGyroflowMuxer(stream, csd, width, height);
        // start() is deferred until addVideoTrack() supplies the real CSD, so
        // the hvcC in moov is valid.
        return new StreamingMuxerSink(muxer);
    }

    /** Open a fragmented MP4 sink (mode 3). The sink owns and closes the channel. */
    FragmentedMp4Sink openFragmentedMuxer() throws IOException {
        java.io.OutputStream stream = resolver.openOutputStream(uri, "rw");
        if (stream == null) {
            throw new IOException("Could not open fMP4 in the selected location");
        }
        java.nio.channels.WritableByteChannel ch =
                java.nio.channels.Channels.newChannel(stream);
        return new FragmentedMp4Sink(ch, stream);
    }

    synchronized void complete() throws IOException {
        if (completed) {
            return;
        }
        if (pendingMedia) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) {
                throw new IOException("Could not publish the MP4 in shared storage");
            }
        }
        completed = true;
    }

    Uri getUri() {
        return uri;
    }

    String getFilename() {
        return filename;
    }

    void writeMetadata(String json) throws IOException {
        String name = filename.replaceFirst("(?i)\\.(mp4|mkv)$", "") + ".json";
        Uri metadataUri;
        if (tree != null) {
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            metadataUri = DocumentsContract.createDocument(resolver, directory,
                    "application/json", name);
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOCUMENTS + "/XREAL/100XREAL");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            metadataUri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
        }
        if (metadataUri == null) {
            throw new IOException("Could not create recording metadata");
        }
        try (OutputStream stream = resolver.openOutputStream(metadataUri, "w")) {
            if (stream == null) {
                throw new IOException("Could not open recording metadata");
            }
            stream.write(json.getBytes(StandardCharsets.UTF_8));
        }
        if (tree == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(metadataUri, values, null, null) != 1) {
                throw new IOException("Could not publish recording metadata");
            }
        }
    }

    void writeGyroCsv(String csv) throws IOException {
        String name = filename.replaceFirst("(?i)\\.(mp4|mkv)$", "") + ".gcsv";
        Uri gyroUri;
        if (tree != null) {
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            gyroUri = DocumentsContract.createDocument(resolver, directory,
                    "application/octet-stream", name);
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            // Same folder as the MP4 so Gyroflow's same-basename sidecar lookup
            // finds it. MediaStore.Files only allows Download/Documents here.
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOCUMENTS + "/XREAL/100XREAL");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            gyroUri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
        }
        if (gyroUri == null) {
            throw new IOException("Could not create recording gyro CSV");
        }
        try (OutputStream stream = resolver.openOutputStream(gyroUri, "w")) {
            if (stream == null) {
                throw new IOException("Could not open recording gyro CSV");
            }
            stream.write(csv.getBytes(StandardCharsets.UTF_8));
        }
        if (tree == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(gyroUri, values, null, null) != 1) {
                throw new IOException("Could not publish recording gyro CSV");
            }
        }
    }

    /**
     * Embeds Gyroflow telemetry into the recorded MP4. SAF tree outputs are left
     * untouched and keep the .gcsv sidecar. The MP4 must be staged in the app
     * cache because mp4parser reads samples lazily while writing the container.
     */
    boolean injectTelemetry(int width, int height, float fps, long durationUs,
                            int imuRateHz, String orientation,
                            List<GyroflowProto.ImuSample> samples, long firstImuTimeUs)
            throws Exception {
        if (tree != null) {
            return false;
        }
        long stamp = System.currentTimeMillis();
        File source = new File(context.getCacheDir(), "inject_source_" + stamp + ".mp4");
        File result = new File(context.getCacheDir(), "inject_result_" + stamp + ".mp4");
        try {
            try (InputStream original = resolver.openInputStream(uri);
                 FileOutputStream copy = new FileOutputStream(source)) {
                if (original == null) {
                    throw new IOException("Could not read recording MP4");
                }
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = original.read(buffer)) >= 0) {
                    copy.write(buffer, 0, count);
                }
            }
            // Append a gyrf trak to a copy of the original moov instead of
            // remuxing with mp4parser: remux reads every sample and breaks when
            // the audio sample list does not match the video duration table.
            Mp4TelemetryInjector.injectWithFfmpeg(context, source, result,
                    width, height, fps, durationUs, imuRateHz, orientation,
                    samples, firstImuTimeUs);
            try (FileInputStream injected = new FileInputStream(result);
                 OutputStream destination = resolver.openOutputStream(uri, "w")) {
                if (destination == null) {
                    throw new IOException("Could not rewrite recording MP4");
                }
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = injected.read(buffer)) >= 0) {
                    destination.write(buffer, 0, count);
                }
            } catch (Exception e) {
                // Restore the original from the copy if the rewrite failed midway.
                // Without this the recording would be left truncated like a
                // partial fMP4 write.
                try (FileInputStream backup = new FileInputStream(source);
                     OutputStream restore = resolver.openOutputStream(uri, "w")) {
                    if (restore != null) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = backup.read(buffer)) >= 0) {
                            restore.write(buffer, 0, count);
                        }
                    }
                } catch (Exception restoreError) {
                    e.addSuppressed(restoreError);
                }
                throw e;
            }
            return true;
        } finally {
            if (source.exists() && !source.delete()) {
                source.deleteOnExit();
            }
            if (result.exists() && !result.delete()) {
                result.deleteOnExit();
            }
        }
    }
}
