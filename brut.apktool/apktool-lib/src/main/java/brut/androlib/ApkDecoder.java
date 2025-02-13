/*
 *  Copyright (C) 2010 Ryszard Wiśniewski <brut.alll@gmail.com>
 *  Copyright (C) 2010 Connor Tumbleson <connor.tumbleson@gmail.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package brut.androlib;

import brut.androlib.exceptions.AndrolibException;
import brut.androlib.exceptions.InFileNotFoundException;
import brut.androlib.exceptions.OutDirExistsException;
import brut.androlib.meta.MetaInfo;
import brut.androlib.res.AndrolibResources;
import brut.androlib.res.data.ResTable;
import brut.androlib.res.data.ResUnknownFiles;
import brut.androlib.src.SmaliDecoder;
import brut.directory.Directory;
import brut.directory.ExtFile;
import brut.common.BrutException;
import brut.directory.DirectoryException;
import brut.util.OS;
import com.android.tools.smali.dexlib2.iface.DexFile;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class ApkDecoder {

    private final static Logger LOGGER = Logger.getLogger(ApkDecoder.class.getName());

    private final Config config;
    private final AndrolibResources mAndRes;
    private final ExtFile mApkFile;
    private ResTable mResTable;
    protected final ResUnknownFiles mResUnknownFiles;
    private Collection<String> mUncompressedFiles;
    private int mMinSdkVersion = 0;

    private final static String SMALI_DIRNAME = "smali";
    private final static String UNK_DIRNAME = "unknown";
    private final static String[] APK_RESOURCES_FILENAMES = new String[] {
        "resources.arsc", "res", "r", "R" };
    private final static String[] APK_MANIFEST_FILENAMES = new String[] {
        "AndroidManifest.xml" };
    private final static String[] APK_STANDARD_ALL_FILENAMES = new String[] {
        "classes.dex", "AndroidManifest.xml", "resources.arsc", "res", "r", "R",
        "lib", "libs", "assets", "META-INF", "kotlin" };
    private final static Pattern NO_COMPRESS_PATTERN = Pattern.compile("(" +
        "jpg|jpeg|png|gif|wav|mp2|mp3|ogg|aac|mpg|mpeg|mid|midi|smf|jet|rtttl|imy|xmf|mp4|" +
        "m4a|m4v|3gp|3gpp|3g2|3gpp2|amr|awb|wma|wmv|webm|webp|mkv)$");

    public ApkDecoder(ExtFile apkFile) {
        this(Config.getDefaultConfig(), apkFile);
    }

    public ApkDecoder(Config config, ExtFile apkFile) {
        this.config = config;
        mAndRes = new AndrolibResources(config);
        mResUnknownFiles = new ResUnknownFiles();
        mApkFile = apkFile;
    }

    public ApkDecoder(File apkFile) {
        this(new ExtFile(apkFile));
    }

    public ApkDecoder(Config config, File apkFile) {
        this(config, new ExtFile(apkFile));
    }

    public void decode(File outDir) throws AndrolibException, IOException, DirectoryException {
        try {
            if (!config.forceDelete && outDir.exists()) {
                throw new OutDirExistsException();
            }

            if (!mApkFile.isFile() || !mApkFile.canRead()) {
                throw new InFileNotFoundException();
            }

            try {
                OS.rmdir(outDir);
            } catch (BrutException ex) {
                throw new AndrolibException(ex);
            }
            outDir.mkdirs();

            LOGGER.info("Using Apktool " + ApktoolProperties.getVersion() + " on " + mApkFile.getName());

            decodeManifest(outDir);
            decodeResources(outDir);

            if (hasSources()) {
                switch (config.decodeSources) {
                    case Config.DECODE_SOURCES_NONE:
                        copySourcesRaw(outDir, "classes.dex");
                        break;
                    case Config.DECODE_SOURCES_SMALI:
                    case Config.DECODE_SOURCES_SMALI_ONLY_MAIN_CLASSES:
                        decodeSourcesSmali(outDir, "classes.dex");
                        break;
                }
            }

            if (hasMultipleSources()) {
                // foreach unknown dex file in root, lets disassemble it
                Set<String> files = mApkFile.getDirectory().getFiles(true);
                for (String file : files) {
                    if (file.endsWith(".dex")) {
                        if (! file.equalsIgnoreCase("classes.dex")) {
                            switch(config.decodeSources) {
                                case Config.DECODE_SOURCES_NONE:
                                    copySourcesRaw(outDir, file);
                                    break;
                                case Config.DECODE_SOURCES_SMALI:
                                    decodeSourcesSmali(outDir, file);
                                    break;
                                case Config.DECODE_SOURCES_SMALI_ONLY_MAIN_CLASSES:
                                    if (file.startsWith("classes") && file.endsWith(".dex")) {
                                        decodeSourcesSmali(outDir, file);
                                    } else {
                                        copySourcesRaw(outDir, file);
                                    }
                                    break;
                            }
                        }
                    }
                }
            }

            copyRawFiles(outDir);
            copyUnknownFiles(outDir);
            mUncompressedFiles = new ArrayList<>();
            recordUncompressedFiles(mUncompressedFiles);
            copyOriginalFiles(outDir);
            writeMetaFile(outDir);
        } finally {
            try {
                mApkFile.close();
            } catch (IOException ignored) {}
        }
    }

    public ResTable getResTable() throws AndrolibException {
        if (mResTable == null) {
            boolean hasResources = hasResources();
            boolean hasManifest = hasManifest();
            if (! (hasManifest || hasResources)) {
                throw new AndrolibException(
                        "Apk doesn't contain either AndroidManifest.xml file or resources.arsc file");
            }
            mResTable = mAndRes.getResTable(mApkFile, hasResources);
            mResTable.setAnalysisMode(config.analysisMode);
        }
        return mResTable;
    }

    public boolean hasSources() throws AndrolibException {
        try {
            return mApkFile.getDirectory().containsFile("classes.dex");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    public boolean hasMultipleSources() throws AndrolibException {
        try {
            Set<String> files = mApkFile.getDirectory().getFiles(false);
            for (String file : files) {
                if (file.endsWith(".dex")) {
                    if (! file.equalsIgnoreCase("classes.dex")) {
                        return true;
                    }
                }
            }

            return false;
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    public boolean hasManifest() throws AndrolibException {
        try {
            return mApkFile.getDirectory().containsFile("AndroidManifest.xml");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    public boolean hasResources() throws AndrolibException {
        try {
            return mApkFile.getDirectory().containsFile("resources.arsc");
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    public void close() throws IOException {
        mAndRes.close();
    }

    private void writeMetaFile(File outDir) throws AndrolibException {
        MetaInfo meta = new MetaInfo();
        meta.version = ApktoolProperties.getVersion();
        meta.apkFileName = mApkFile.getName();

        if (mResTable != null) {
            mResTable.initMetaInfo(meta, outDir);
            if (config.frameworkTag != null) {
                meta.usesFramework.tag = config.frameworkTag;
            }
        } else {
            if (mMinSdkVersion > 0) {
                meta.sdkInfo = getMinSdkInfo();
            }
        }
        meta.unknownFiles = mResUnknownFiles.getUnknownFiles();
        if (mUncompressedFiles != null && !mUncompressedFiles.isEmpty()) {
            meta.doNotCompress = mUncompressedFiles;
        }

        try {
            meta.save(new File(outDir, "apktool.yml"));
        } catch (IOException ex) {
            throw new AndrolibException(ex);
        }
    }

    private Map<String, String> getMinSdkInfo() {
        Map<String, String> sdkInfo = new LinkedHashMap<>();
        sdkInfo.put("minSdkVersion", Integer.toString(mMinSdkVersion));
        return sdkInfo;
    }

    private void copySourcesRaw(File outDir, String filename)
        throws AndrolibException {
        try {
            LOGGER.info("Copying raw " + filename + " file...");
            mApkFile.getDirectory().copyToDir(outDir, filename);
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void decodeSourcesSmali(File outDir, String filename)
        throws AndrolibException {
        try {
            File smaliDir;
            if (filename.equalsIgnoreCase("classes.dex")) {
                smaliDir = new File(outDir, SMALI_DIRNAME);
            } else {
                smaliDir = new File(outDir, SMALI_DIRNAME + "_" + filename.substring(0, filename.indexOf(".")));
            }
            OS.rmdir(smaliDir);
            //noinspection ResultOfMethodCallIgnored
            smaliDir.mkdirs();
            LOGGER.info("Baksmaling " + filename + "...");
            DexFile dexFile = SmaliDecoder.decode(mApkFile, smaliDir, filename,
                config.baksmaliDebugMode, config.apiLevel);
            int minSdkVersion = dexFile.getOpcodes().api;
            if (mMinSdkVersion == 0 || mMinSdkVersion > minSdkVersion) {
                mMinSdkVersion = minSdkVersion;
            }
        } catch (BrutException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void decodeManifest(File outDir) throws AndrolibException {
        if (hasManifest()) {
            if (config.decodeResources == Config.DECODE_RESOURCES_FULL ||
                config.forceDecodeManifest == Config.FORCE_DECODE_MANIFEST_FULL) {
                if (hasResources()) {
                    mAndRes.decodeManifestWithResources(getResTable(), mApkFile, outDir);
                } else {
                    // if there's no resources.arsc, decode the manifest without looking
                    // up attribute references
                    mAndRes.decodeManifest(getResTable(), mApkFile, outDir);
                }
            }
            else {
                try {
                    LOGGER.info("Copying raw manifest...");
                    mApkFile.getDirectory().copyToDir(outDir, APK_MANIFEST_FILENAMES);
                } catch (DirectoryException ex) {
                    throw new AndrolibException(ex);
                }
            }
        }
    }

    private void decodeResources(File outDir) throws AndrolibException {
        if (hasResources()) {
            switch (config.decodeResources) {
                case Config.DECODE_RESOURCES_NONE:
                    try {
                        LOGGER.info("Copying raw resources...");
                        mApkFile.getDirectory().copyToDir(outDir, APK_RESOURCES_FILENAMES);
                    } catch (DirectoryException ex) {
                        throw new AndrolibException(ex);
                    }
                    break;
                case Config.DECODE_RESOURCES_FULL:
                    mAndRes.decode(getResTable(), mApkFile, outDir);
                    break;
            }
        }
    }

    private void copyRawFiles(File outDir)
        throws AndrolibException {
        LOGGER.info("Copying assets and libs...");
        try {
            Directory in = mApkFile.getDirectory();

            if (config.decodeAssets == Config.DECODE_ASSETS_FULL) {
                if (in.containsDir("assets")) {
                    in.copyToDir(outDir, "assets");
                }
            }
            if (in.containsDir("lib")) {
                in.copyToDir(outDir, "lib");
            }
            if (in.containsDir("libs")) {
                in.copyToDir(outDir, "libs");
            }
            if (in.containsDir("kotlin")) {
                in.copyToDir(outDir, "kotlin");
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private boolean isAPKFileNames(String file) {
        for (String apkFile : APK_STANDARD_ALL_FILENAMES) {
            if (apkFile.equals(file) || file.startsWith(apkFile + "/")) {
                return true;
            }
        }
        return false;
    }

    private void copyUnknownFiles(File outDir)
        throws AndrolibException {
        LOGGER.info("Copying unknown files...");
        File unknownOut = new File(outDir, UNK_DIRNAME);
        try {
            Directory unk = mApkFile.getDirectory();

            // loop all items in container recursively, ignoring any that are pre-defined by aapt
            Set<String> files = unk.getFiles(true);
            for (String file : files) {
                if (!isAPKFileNames(file) && !file.endsWith(".dex")) {

                    // copy file out of archive into special "unknown" folder
                    unk.copyToDir(unknownOut, file);
                    // let's record the name of the file, and its compression type
                    // so that we may re-include it the same way
                    mResUnknownFiles.addUnknownFileInfo(file, String.valueOf(unk.getCompressionLevel(file)));
                }
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void copyOriginalFiles(File outDir)
        throws AndrolibException {
        LOGGER.info("Copying original files...");
        File originalDir = new File(outDir, "original");
        if (!originalDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            originalDir.mkdirs();
        }

        try {
            Directory in = mApkFile.getDirectory();
            if (in.containsFile("AndroidManifest.xml")) {
                in.copyToDir(originalDir, "AndroidManifest.xml");
            }
            if (in.containsFile("stamp-cert-sha256")) {
                in.copyToDir(originalDir, "stamp-cert-sha256");
            }
            if (in.containsDir("META-INF")) {
                in.copyToDir(originalDir, "META-INF");

                if (in.containsDir("META-INF/services")) {
                    // If the original APK contains the folder META-INF/services folder
                    // that is used for service locators (like coroutines on android),
                    // copy it to the destination folder, so it does not get dropped.
                    LOGGER.info("Copying META-INF/services directory");
                    in.copyToDir(outDir, "META-INF/services");
                }
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }

    private void recordUncompressedFiles(Collection<String> uncompressedFilesOrExts) throws AndrolibException {
        try {
            Directory unk = mApkFile.getDirectory();
            Set<String> files = unk.getFiles(true);

            for (String file : files) {
                if (isAPKFileNames(file) && unk.getCompressionLevel(file) == 0) {
                    String extOrFile = "";
                    if (unk.getSize(file) != 0) {
                        extOrFile = FilenameUtils.getExtension(file);
                    }

                    if (extOrFile.isEmpty() || !NO_COMPRESS_PATTERN.matcher(extOrFile).find()) {
                        extOrFile = file;
                        if (mAndRes.mResFileMapping.containsKey(extOrFile)) {
                            extOrFile = mAndRes.mResFileMapping.get(extOrFile);
                        }
                    }
                    if (!uncompressedFilesOrExts.contains(extOrFile)) {
                        uncompressedFilesOrExts.add(extOrFile);
                    }
                }
            }
        } catch (DirectoryException ex) {
            throw new AndrolibException(ex);
        }
    }
}
