package com.syameimarukoa.xreal_recorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
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
    private final File outputFile;
    private RecordingMuxer rawSink;
    private boolean completed;

    private RecordingOutput(Context context, ContentResolver resolver, Uri uri,
                            boolean pendingMedia, String filename, Uri tree, File outputFile) {
        this.context = context;
        this.resolver = resolver;
        this.uri = uri;
        this.pendingMedia = pendingMedia;
        this.filename = filename;
        this.tree = tree;
        this.outputFile = outputFile;
    }

    static RecordingOutput create(Context context, Uri tree, long captureTimeMs) throws IOException {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (dir == null) {
            dir = new File(context.getFilesDir(), "Movies");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create output directory");
        }
        String filename = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date(captureTimeMs)) + ".mp4";
        return new RecordingOutput(context, context.getContentResolver(), null,
                false, filename, tree, new File(dir, filename));
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

    /** One continuous write to the app-owned file; MediaScanner publishes it. */
    RecordingMuxer openFragmentedMuxer() throws IOException {
        File hevcSeed = new File(context.getCacheDir(), "xreal_seed.hevc");
        File aacSeed = new File(context.getCacheDir(), "xreal_seed.aac");
        for (File f : new File[]{outputFile, hevcSeed, aacSeed}) {
            if (f.exists() && !f.delete()) {
                throw new IOException("Could not clear " + f.getName());
            }
        }
        rawSink = new StreamingMuxSink(outputFile, hevcSeed, aacSeed);
        return rawSink;
    }

    synchronized void complete() throws IOException {
        if (completed) {
            return;
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
    /** Append gyrf to the MP4 already written to the uri; no second copy. */
    boolean injectTelemetry(int width, int height, float fps, long durationUs,
                            int imuRateHz, String orientation,
                            List<GyroflowProto.ImuSample> samples, long firstImuTimeUs)
            throws Exception {
        if (tree != null) {
            return false;
        }
        if (!(rawSink instanceof StreamingMuxSink)) {
            return false;
        }
        StreamingMuxSink sink = (StreamingMuxSink) rawSink;
        try {
            Mp4TelemetryInjector.appendGyrf(sink, width, height, fps,
                    durationUs, imuRateHz, orientation, samples, firstImuTimeUs);
            MediaScannerConnection.scanFile(context,
                    new String[]{outputFile.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            return true;
        } catch (Exception e) {
            sink.discard();
            return false;
        }
    }
}