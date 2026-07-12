package com.fastexplorer.ui;

import com.fastexplorer.model.BookmarkNode;

public record BookmarkGrepPreset(
        String pattern,
        String path,
        String file,
        String extension,
        boolean regex,
        boolean recursive,
        int context
) {
    public boolean isEmpty() {
        return pattern == null || pattern.isBlank();
    }

    public String defaultName() {
        BookmarkNode node = BookmarkNode.grep("", pattern, path, file, extension, regex, recursive, context);
        String detail = node.detailText();
        return detail.isBlank() ? "Grep条件" : detail;
    }
}
