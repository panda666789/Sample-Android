package com.tsinghua.sample.patient;

import com.tsinghua.sample.patient.storage.PatientRecord;
import com.tsinghua.sample.patient.storage.SessionDirectoryInfo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class SessionDirectoryInfoTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void resolvesTheRecordsOwnSessionIncludingChineseAndSpaces() throws Exception {
        File storage = temporary.newFolder("storage");
        File first = session(storage, "门诊 测试", "Session_123");
        session(storage, "门诊 测试", "Session_999");
        SessionDirectoryInfo info = SessionDirectoryInfo.resolve(record(first), storage);
        assertEquals(first.getCanonicalFile(), info.directory);
        assertEquals("primary:Movies/FacialCollection/门诊 测试/Session_123", info.documentId);
        assertTrue(info.videoLocationMessage().contains("front 文件夹"));
        assertTrue(info.videoLocationMessage().contains("back 文件夹"));
        assertTrue(info.videoLocationMessage().contains("Session_123"));
        assertFalse(info.videoLocationMessage().contains("Session_999"));
    }

    @Test public void legacyInfoRecordsResolveToTheSameSession() throws Exception {
        File storage = temporary.newFolder("storage");
        File session = session(storage, "default", "Session_456");
        PatientRecord legacy = new PatientRecord(new File(session, "info/subject_info.txt"), session, null);
        assertEquals(session.getCanonicalFile(), SessionDirectoryInfo.resolve(legacy, storage).directory);
    }

    @Test public void missingDirectoryIsNotSilentlyCreated() throws Exception {
        File storage = temporary.newFolder("storage");
        File missing = new File(storage, "Movies/FacialCollection/default/Session_1");
        assertRejected(record(missing), storage);
        assertFalse(missing.exists());
    }

    @Test public void rejectsSiblingWithSimilarPrefixAndOutsideStorage() throws Exception {
        File storage = temporary.newFolder("storage");
        File sibling = new File(storage, "Movies/FacialCollection-copy/default/Session_1");
        assertTrue(sibling.mkdirs());
        assertRejected(record(sibling), storage);
        assertRejected(record(temporary.newFolder("Session_2")), storage);
    }

    @Test public void rejectsSymlinkEscapeAndNonSessionDirectory() throws Exception {
        File storage = temporary.newFolder("storage");
        File root = new File(storage, "Movies/FacialCollection/default");
        assertTrue(root.mkdirs());
        File outside = temporary.newFolder("Session_9");
        File link = new File(root, "Session_9");
        Files.createSymbolicLink(link.toPath(), outside.toPath());
        assertRejected(record(link), storage);
        assertRejected(record(root), storage);
    }

    @Test public void rejectsMissingAssociationAndFileInsteadOfDirectory() throws Exception {
        File storage = temporary.newFolder("storage");
        assertRejected(null, storage);
        assertRejected(new PatientRecord(null, null, null), storage);
        File file = new File(storage, "Movies/FacialCollection/default/Session_8");
        assertTrue(file.getParentFile().mkdirs());
        assertTrue(file.createNewFile());
        assertRejected(record(file), storage);
    }

    private static File session(File storage, String experiment, String name) {
        File file = new File(storage, "Movies/FacialCollection/" + experiment + "/" + name);
        assertTrue(file.mkdirs());
        return file;
    }

    private static PatientRecord record(File session) {
        return new PatientRecord(new File(session, "PatientInfo/patient.json"), session, null);
    }

    private static void assertRejected(PatientRecord record, File storage) throws Exception {
        try {
            SessionDirectoryInfo.resolve(record, storage);
            fail("Should reject missing or outside session directory");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
