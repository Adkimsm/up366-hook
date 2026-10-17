package io.github.adkimsm.up366.hook;

import java.io.File;

/**
 * Stable identity of one downloaded question-package page, derived from its directory layout
 * {@code <book>/<chapter>/<page>/1.html}. The {@code packageVersion} is a hash of the book
 * directory so it stays stable across restarts without exposing the absolute path.
 */
final class PackageIdentity {
    final String packageVersion;
    final String bookId;
    final String chapterId;
    final String pageId;

    PackageIdentity(String packageVersion, String bookId, String chapterId, String pageId) {
        this.packageVersion = packageVersion;
        this.bookId = bookId;
        this.chapterId = chapterId;
        this.pageId = pageId;
    }

    static PackageIdentity of(File pageFile) {
        if (pageFile == null) return new PackageIdentity("", "", "", "");
        File pageDirectory = pageFile.getParentFile();
        File chapterDirectory = pageDirectory == null ? null : pageDirectory.getParentFile();
        File bookDirectory = chapterDirectory == null ? null : chapterDirectory.getParentFile();
        return new PackageIdentity(
                bookDirectory == null ? "" : Trace.hash(canonical(bookDirectory)),
                name(bookDirectory), name(chapterDirectory), name(pageDirectory));
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    private static String name(File directory) {
        return directory == null ? "" : directory.getName();
    }
}
