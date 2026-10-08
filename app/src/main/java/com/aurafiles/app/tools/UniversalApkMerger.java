/*
 * Aura Files split APK merger.
 *
 * Merge/sanitization logic is adapted from REAndroid/APKEditor 1.4.9 and
 * REAndroid/ARSCLib, Copyright (C) REAndroid contributors, licensed under
 * the Apache License, Version 2.0.
 *
 * This adaptation intentionally embeds only the merge behavior needed by
 * Aura Files; it does not embed APKEditor's CLI, smali or JCommand layers.
 */
package com.aurafiles.app.tools;

import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.ApkUtil;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.ZipEntryMap;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.container.SpecTypePair;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResValue;
import com.reandroid.arsc.value.ValueType;
import com.reandroid.utils.collection.CollectionUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Fuses base.apk + installed split APKs into one unsigned APK. */
public final class UniversalApkMerger {
    private static final Pattern STALE_V1_SIGNATURE = Pattern.compile(
            "^META-INF/[^/]+\\.((SF)|(RSA)|(DSA)|(EC))$",
            Pattern.CASE_INSENSITIVE
    );

    private UniversalApkMerger() {}

    public static void merge(List<File> apkFiles, File output) throws IOException {
        if (apkFiles == null || apkFiles.size() < 2) {
            throw new IllegalArgumentException("Для объединения нужны base.apk и хотя бы один split APK");
        }
        if (output == null) {
            throw new IllegalArgumentException("Не задан выходной APK");
        }
        File parent = output.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Не удалось подготовить каталог для единого APK");
        }
        if (output.exists() && !output.delete()) {
            throw new IOException("Не удалось удалить старый временный APK");
        }

        ApkBundle bundle = new ApkBundle();
        ApkModule merged = null;
        try {
            Set<String> canonicalInputs = new HashSet<>();
            Set<String> moduleNames = new HashSet<>();
            for (File apk : apkFiles) {
                if (apk == null || !apk.isFile() || apk.length() <= 0L) {
                    throw new IOException("Недоступна APK-часть: " + (apk == null ? "null" : apk.getName()));
                }
                String canonical = apk.getCanonicalPath();
                if (!canonicalInputs.add(canonical)) {
                    throw new IOException("Повтор APK-части: " + apk.getName());
                }
                String moduleName = ApkUtil.toModuleName(apk);
                if (!moduleNames.add(moduleName.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Конфликт имён SPLIT-модулей: " + moduleName);
                }
                ApkModule module = ApkModule.loadApkFile(apk, moduleName);
                bundle.addModule(module);
            }
            if (bundle.countModules() != apkFiles.size()) {
                throw new IOException("Не все SPLIT-модули были загружены");
            }

            merged = bundle.mergeModules(false);
            sanitizeManifest(merged);
            clearStaleSignatures(merged);
            merged.refreshTable();
            merged.refreshManifest();
            applyExtractNativeLibs(merged);
            merged.writeApk(output);
            if (!output.isFile() || output.length() <= 0L) {
                throw new IOException("Объединённый APK не был создан");
            }
        } finally {
            if (merged != null) {
                try {
                    merged.close();
                } catch (Throwable ignored) {
                    // Best-effort resource cleanup; preserve the original merge failure.
                }
            }
            try {
                bundle.close();
            } catch (Throwable ignored) {
                // Best-effort resource cleanup; preserve the original merge failure.
            }
        }
    }

    private static void clearStaleSignatures(ApkModule module) {
        ZipEntryMap archive = module.getZipEntryMap();
        archive.removeIf(STALE_V1_SIGNATURE);
        archive.remove("stamp-cert-sha256");
        module.setApkSignatureBlock(null);
    }

    private static void applyExtractNativeLibs(ApkModule module) {
        Boolean extractNativeLibs = null;
        if (module.hasAndroidManifest()) {
            extractNativeLibs = module.getAndroidManifest().isExtractNativeLibs();
        }
        module.setExtractNativeLibs(extractNativeLibs);
    }

    private static void sanitizeManifest(ApkModule apkModule) {
        if (!apkModule.hasAndroidManifest()) {
            return;
        }
        AndroidManifestBlock manifest = apkModule.getAndroidManifest();
        removeAttributeFromManifestById(manifest, AndroidManifest.ID_requiredSplitTypes);
        removeAttributeFromManifestById(manifest, AndroidManifest.ID_splitTypes);
        removeAttributeFromManifestByName(manifest, AndroidManifest.NAME_requiredSplitTypes);
        removeAttributeFromManifestByName(manifest, AndroidManifest.NAME_splitTypes);
        removeAttributeFromManifestAndApplication(manifest, AndroidManifest.ID_isSplitRequired);

        ResXmlElement application = manifest.getApplicationElement();
        for (ResXmlElement meta : listSplitRequired(application)) {
            removeSplitsTableEntry(meta, apkModule);
            application.remove(meta);
        }
        manifest.refresh();
    }

    private static List<ResXmlElement> listSplitRequired(ResXmlElement parentElement) {
        List<ResXmlElement> result = new ArrayList<>();
        if (parentElement == null) {
            return result;
        }
        Iterator<ResXmlElement> iterator = parentElement.getElements(element -> {
            if (!element.equalsName(AndroidManifest.TAG_meta_data)) {
                return false;
            }
            ResXmlAttribute nameAttribute = CollectionUtil.getFirst(
                    element.getAttributes(UniversalApkMerger::isNameResourceId)
            );
            if (nameAttribute == null || nameAttribute.getValueType() != ValueType.STRING) {
                return false;
            }
            String value = nameAttribute.getValueAsString();
            if (value == null) {
                return false;
            }
            if ("com.android.dynamic.apk.fused.modules".equals(value)) {
                return true;
            }
            return value.startsWith("com.android.vending.") || value.startsWith("com.android.stamp.");
        });
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }

    private static boolean isNameResourceId(ResXmlAttribute attribute) {
        return attribute.getNameId() == AndroidManifest.ID_name;
    }

    private static void removeAttributeFromManifestByName(AndroidManifestBlock manifest, String resourceName) {
        ResXmlElement root = manifest.getManifestElement();
        if (root != null) {
            root.removeAttributesWithName(resourceName);
        }
    }

    private static void removeAttributeFromManifestById(AndroidManifestBlock manifest, int resourceId) {
        ResXmlElement root = manifest.getManifestElement();
        if (root != null) {
            root.removeAttributesWithId(resourceId);
        }
    }

    private static void removeAttributeFromManifestAndApplication(AndroidManifestBlock manifest, int resourceId) {
        ResXmlElement root = manifest.getManifestElement();
        if (root == null || resourceId == 0) {
            return;
        }
        root.removeAttributesWithId(resourceId);
        ResXmlElement application = root.getElement(AndroidManifest.TAG_application);
        if (application != null) {
            application.removeAttributesWithId(resourceId);
        }
    }

    private static boolean removeSplitsTableEntry(ResXmlElement metaElement, ApkModule apkModule) {
        ResXmlAttribute nameAttribute = metaElement.searchAttributeByResourceId(AndroidManifest.ID_name);
        if (nameAttribute == null || !"com.android.vending.splits".equals(nameAttribute.getValueAsString())) {
            return false;
        }
        ResXmlAttribute valueAttribute = metaElement.searchAttributeByResourceId(AndroidManifest.ID_value);
        if (valueAttribute == null) {
            valueAttribute = metaElement.searchAttributeByResourceId(AndroidManifest.ID_resource);
        }
        if (valueAttribute == null || valueAttribute.getValueType() != ValueType.REFERENCE || !apkModule.hasTableBlock()) {
            return false;
        }

        TableBlock tableBlock = apkModule.getTableBlock();
        ResourceEntry resourceEntry = tableBlock.getResource(valueAttribute.getData());
        if (resourceEntry == null) {
            return false;
        }
        ZipEntryMap zipEntryMap = apkModule.getZipEntryMap();
        for (Entry entry : resourceEntry) {
            if (entry == null) {
                continue;
            }
            ResValue resValue = entry.getResValue();
            if (resValue == null) {
                continue;
            }
            String path = resValue.getValueAsString();
            if (path != null) {
                zipEntryMap.remove(path);
            }
            // Preserve the resource ID in case app dex still references it.
            entry.setNull(true);
            SpecTypePair pair = entry.getTypeBlock().getParentSpecTypePair();
            pair.removeNullEntries(entry.getId());
        }
        return true;
    }
}
