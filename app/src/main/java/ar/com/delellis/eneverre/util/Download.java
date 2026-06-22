package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class Download {

    public static File getDownloadFile(String prefix, String dateTime, String extension) {
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS + "/Eneverre");
        downloadFolder.mkdirs();

        String fileName = prefix + "_" + dateTime + "." + extension;

        return new File(downloadFolder.getPath() + "/" + fileName);
    }

    public static void writeFile(Context context, File destFile, byte[] content) throws IOException {
        FileOutputStream output = new FileOutputStream(destFile);
        output.write(content);
        output.close();

        String[] absPath = new String[]{destFile.getAbsolutePath()};
        MediaScannerConnection.scanFile(context, absPath, null, null);
    }

    public static void share(Context context, Uri uri, String title, String mimetype) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.setType(mimetype);
        shareIntent.putExtra(Intent.EXTRA_TITLE, title);

        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.setClipData(
                android.content.ClipData.newUri(
                        context.getContentResolver(),
                        "image",
                        uri
                )
        );

        Intent chooser = Intent.createChooser(shareIntent, title);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(chooser);
    }

    public static void open(Context context, Uri uri, String mimetype) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimetype);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }
}