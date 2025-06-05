package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.content.Intent;
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

    public static void writeFile(byte[] content, File destFile) throws IOException {
        FileOutputStream output = new FileOutputStream(destFile);
        output.write(content);
        output.close();
    }

    public static void share(Context context, Uri uri, String title, String mimetype) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.setType(mimetype);
        context.startActivity(Intent.createChooser(shareIntent, title));
    }

}