package com.offsec.nethunter;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.Fragment;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;


import java.io.File;
import java.util.GregorianCalendar;

/**
 * Created by fattire on 3/14/15.
 * This is GPLv2'd.
 *
 * This was quickly thrown together:
 *
 * TO DO:
 *
 * * Actually verify SHA of downloaded chroot file
 * * Non-arm arch support
 * * better UI (it locks up currently when untarring/zipping file...)
 * * Add "are you sure" dialog before wiping/reinstalling
 * *  Handle situations where user opens this fragment multiple times or
 * quits during download or re-opens during download, etc.  that may
 * mean making this part of its own activity or whatever...
 * * Move strings to string resources.
 * * Clean up and make it betterer
 * * Figure why "/storage/emulated/0" -> "/storage/emulated/legacy" replacement is necessary
 * on some devices
 */


public class CreateChrootFragment extends Fragment {


    private static final String ARG_SECTION_NUMBER = "section_number";
    private static final String ARCH = System.getProperty("os.arch");
    private static final String TAG = "CreateChroot";

    /* put chroot info here */

    private static final String FILENAME = "kalifs.tar.xz";
    private static final String URI = "http://3bollcdn.com/nethunter/chroot/" + FILENAME;
  //  private static final String SHA = "PUT SHA HERE"; // not yet implemented

    String zipFilePath;
    private long downloadRef;
    TextView statusText;
    String chrootPath;
    Button installButton;
    DownloadManager dm;
    BroadcastReceiver onDLFinished;
    String dir;
    final ShellExecuter x = new ShellExecuter();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.createchroot, container, false);
        statusText = (TextView) rootView.findViewById(R.id.statusText);
        installButton = (Button) rootView.findViewById(R.id.installButton);
        installButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onButtonHit();
            }
        });

        zipFilePath = getActivity().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) + "/" + FILENAME;

        onDLFinished = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadRef) {
                    downloadFinished();
                }
            }
        };

        getActivity().registerReceiver(onDLFinished,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        return rootView;
    }

    public static CreateChrootFragment newInstance(int sectionNumber) {
        CreateChrootFragment fragment = new CreateChrootFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_SECTION_NUMBER, sectionNumber);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        // which chroot?

        if (ARCH.contains("arm")) {
            dir = "kali-armhf";
        } else if (ARCH.contains("i686")) {
            dir = "kali-amd64";
        } else if (ARCH.contains("mips")) {
            dir = "kali-mips";
        } else if (ARCH.contains("x86")) {
            dir = "kali-i386";  // etc
        }
        checkForExistingChroot();
        super.onActivityCreated(savedInstanceState);
    }

    private void checkForExistingChroot() {
        // does chroot directory exist?

        chrootPath = getActivity().getFilesDir() + "/chroot/";
        statusLog("checking inn chroot: " + chrootPath);
        File file = new File(chrootPath + dir + "/");
        statusLog(chrootPath + dir);

        final ShellExecuter exe = new ShellExecuter();
        String command = "if [ -d "+chrootPath + dir+" ];then echo 1; fi"; //check the dir existence
        final String _res;

        _res = exe.RunAsRootOutput(command);

        if (_res.equals("1")) {
            statusLog("An existing Kali chroot directory was found!");
            installButton.setText("Wipe and reinstall chroot");
        } else {
            statusLog("No Kali chroot directory was found.");
            file.mkdir();
            installButton.setText("Install chroot");
        }
    }

    private void onButtonHit() {
        installButton.setEnabled(false);

        String command = "if [ -d "+chrootPath + dir+" ];then echo 1; fi"; //check the dir existence
        final String _res;

        _res = x.RunAsRootOutput(command);

        if (_res.equals("1")) {
            statusLog("Existing chroot found.  Deleting it.");
            removeChroot();
        }
        if (!startZipDownload()) {
            installButton.setEnabled(true);
        }
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    private boolean startZipDownload() {

        Log.d(TAG, "starting ~70MB download... Once it downloads, BE REALLY PATIENT as the UI will lock up until we fix this.");
        File checkFile = new File(zipFilePath);
        if (checkFile.exists()) {
            statusLog(zipFilePath + " exists already.");
            if (checkFileIntegrity(zipFilePath)) {
                statusLog("The file looks good, so no need to re-download it.");
                inflateZip();
                return true;
            } else {
                statusLog("Deleting to make room for download...");
                if (checkFile.delete()) {
                    statusLog("Old file deleted.");
                } else {
                    statusLog("Problem deleting old file.  Just sayin'.");
                }
            }
        }
        statusLog("Starting download...");
        if (!isExternalStorageWritable()) {
            statusLog("Nowhere to write to.  Make sure you have your external storage mounted and available.");
            return false;
        }
        dm = (DownloadManager) getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        removeExistingDownloadOperations();
        Uri uri = Uri.parse(URI);
        DownloadManager.Request r = new DownloadManager.Request(uri);
        r.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
                .setAllowedOverMetered(true)
                .setTitle(FILENAME)
                .setDescription("Downloading base Kali chroot")
                .setAllowedOverRoaming(true)
                .setDestinationInExternalFilesDir(getActivity(), Environment.DIRECTORY_DOWNLOADS, FILENAME);

        downloadRef = dm.enqueue(r);
        return true;
    }

    private void downloadFinished() {
        int status = -1;
        // what happened
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(downloadRef);
        Cursor c = dm.query(q);
        if (c.moveToFirst()) {
            status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            statusLog("Download completed successfully.");
            inflateZip();
            installButton.setEnabled(true);
        } else {
            statusLog("Download failed.  Check your network connection and external storage and try again.");
            installButton.setEnabled(true);
        }
        dm.remove(downloadRef);
    }

    private void inflateZip() {
        statusLog("INFLATING...");
        // it's possible that there is a missing symlink, so check.
        try {
            x.RunAsRootWithException("ls '" + zipFilePath + '\'');
        } catch (RuntimeException ex) { // file not found
            zipFilePath = zipFilePath.replace("/storage/emulated/0", "/storage/emulated/legacy");
        }

        if (checkFileIntegrity(zipFilePath)) {
            statusLog("Extracting to chroot.  Standby...");
            // all In bg
            createDir();

        }
    }

    private void removeChroot(){
        new Thread(new Runnable() {
            public void run() {

                try {
                    statusLog("Deleting Dirs...");
                    x.RunAsRootOutput("rm -rf '" + chrootPath + dir + '\'');

                    statusText.post(new Runnable() {
                        public void run() {
                            statusLog("chroot delete ==> successful");


                        }
                    });

                } catch (Exception e) {
                    statusText.post(new Runnable() {
                        public void run() {
                            statusLog("ERROR deleting DIR");
                        }
                    });
                }
            }
        }).start();



    }
    private void createDir(){
        new Thread(new Runnable() {
            public void run() {

                try {

                    statusLog("Creating Dirs...");
                    x.RunAsRootOutput("mkdir -p " + getActivity().getFilesDir() + "/chroot");


                    statusText.post(new Runnable() {
                        public void run() {

                            statusLog("DIR CREATED");
                            //go to unxz
                            unXZ();
                        }
                    });

                } catch (Exception e) {
                    statusText.post(new Runnable() {
                        public void run() {
                            statusLog("ERROR CREATING DIR");
                        }
                    });
                }
            }
        }).start();



    }
    private void unXZ(){

        new Thread(new Runnable() {
            public void run() {

                try {

                    statusLog("1st UNPACK ==> .xz > .tar");
                    x.RunAsRootOutput("busybox xz -d '" + zipFilePath + '\'');

                    statusText.post(new Runnable() {
                        public void run() {

                            statusLog("unxz done");
                            // lets untar
                            unTAR();
                        }
                    });

                } catch (Exception e) {
                    statusText.post(new Runnable() {
                        public void run() {
                            statusLog("ERROR IN BUSIBOX XZ");
                        }
                    });
                }
            }
        }).start();


    }
    private void unTAR(){

        new Thread(new Runnable() {
            public void run() {

                try {

                    statusLog("2nd UNPACK ==> .tar > fs");
                    x.RunAsRootOutput("busybox tar xf '" + zipFilePath.substring(0, zipFilePath.lastIndexOf('.')) + "' -C '" + chrootPath + "'");

                    statusText.post(new Runnable() {
                        public void run() {

                            statusLog("untar done");
                            statusLog("We're all done.  If there are errors, that's a problem.");
                            installButton.setEnabled(true);
                            statusLog("Final check:  is the directory there...?");
                            checkForExistingChroot();
                        }
                    });


                } catch (Exception e) {
                    statusText.post(new Runnable() {
                        public void run() {
                            statusLog("ERROR IN BUSIBOX TAR xf");
                        }
                    });
                }
            }
        }).start();


    }
    private boolean checkFileIntegrity(String path) {
        statusLog("TO DO:  Check file integrity.");
        return true;
    }

    private void statusLog(String status) {
        GregorianCalendar cal = new GregorianCalendar();
        String ts = String.valueOf(cal.get(GregorianCalendar.MONTH)) + '/' +
                String.valueOf(cal.get(GregorianCalendar.DAY_OF_MONTH)) + '/' +
                String.valueOf(cal.get(GregorianCalendar.YEAR)) + ' ' +
                String.valueOf(cal.get(GregorianCalendar.HOUR_OF_DAY)) + ':' +
                String.valueOf(cal.get(GregorianCalendar.MINUTE)) + ':' +
                String.valueOf(cal.get(GregorianCalendar.SECOND)) + '.' +
                String.valueOf(cal.get(GregorianCalendar.MILLISECOND)) + " - ";
        statusText.append(Html.fromHtml("<font color=\"#EDA04F\">" + ts + "</font>"));  // weird men! :)
        statusText.append(status + '\n');
    }

    private void removeExistingDownloadOperations() {
        int status;

        DownloadManager.Query q = new DownloadManager.Query();
        Cursor c = dm.query(q);
        if (c.moveToFirst()) {
            status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_ID));
            dm.remove(status);
        }
    }

}

