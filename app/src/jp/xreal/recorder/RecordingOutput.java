package jp.xreal.recorder;

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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates a user-visible MP4 without requiring direct filesystem access. */
final class RecordingOutput {
    private final ContentResolver resolver;
    private final Uri uri;
    private final boolean pendingMedia;
    private final String filename;
    private final Uri tree;
    private boolean completed;

    private RecordingOutput(ContentResolver resolver, Uri uri, boolean pendingMedia,
                            String filename, Uri tree) {
        this.resolver = resolver;
        this.uri = uri;
        this.pendingMedia = pendingMedia;
        this.filename = filename;
        this.tree = tree;
    }

    static RecordingOutput create(Context context, Uri tree, long captureTimeMs, boolean mjpeg) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri uri;
        boolean pending = tree == null;
        String extension = mjpeg ? "mkv" : "mp4";
        String mimeType = mjpeg ? "video/x-matroska" : "video/mp4";
        String filename = nextFilename(resolver, tree, extension);
        if (tree != null) {
            Uri directory = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            uri = DocumentsContract.createDocument(resolver, directory, mimeType, filename);
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
            values.put(MediaStore.Video.Media.MIME_TYPE, mimeType);
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
        return new RecordingOutput(resolver, uri, pending, actualName, tree);
    }

    private static String nextFilename(ContentResolver resolver, Uri tree, String extension) throws IOException {
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
        Pattern pattern = Pattern.compile("VID_([0-9]+)\\.(mp4|mkv)", Pattern.CASE_INSENSITIVE);
        long max = 0;
        try (Cursor cursor = resolver.query(children,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, selection, selectionArgs, null)) {
            if (cursor == null) {
                throw new IOException("Could not list recordings in the selected location");
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                Matcher match = pattern.matcher(name == null ? "" : name);
                if (match.matches()) {
                    try {
                        max = Math.max(max, Long.parseLong(match.group(1)));
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid recording number: " + name, e);
                    }
                }
            }
        }
        if (max == Long.MAX_VALUE) {
            throw new IOException("Recording number limit reached");
        }
        return String.format(Locale.US, "VID_%04d.%s", max + 1, extension);
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

    RecordingMuxer openMatroskaMuxer() throws IOException {
        OutputStream stream = resolver.openOutputStream(uri, "w");
        if (stream == null) {
            throw new IOException("Could not open Matroska output");
        }
        return new MatroskaMuxer(stream);
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
}
