package com.lenovo.parts;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DesktopState {
    private static final Pattern USER = Pattern.compile("userId=(\\d+)");
    private static final Pattern DISPLAY = Pattern.compile("Display #(\\d+):");
    private static final Pattern DESK = Pattern.compile("Desk #(\\d+):");
    private static final Pattern TASKS = Pattern.compile("activeTasks=\\[([0-9, ]*)\\]");

    static Map<Integer, Set<Integer>> orphanCandidates(String dump, int userId) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        int sectionIndent = -1;
        int user = -1;
        int display = -1;
        int desk = -1;
        Set<Integer> tasks = null;
        for (String line : dump.split("\\R")) {
            String text = line.trim();
            if (text.isEmpty()) continue;
            int indent = line.indexOf(text);
            if (sectionIndent < 0) {
                if (text.equals("DesktopUserRepositories:")) sectionIndent = indent;
                continue;
            }
            if (indent <= sectionIndent) break;
            Matcher match;
            if ((match = USER.matcher(text)).matches()) {
                user = Integer.parseInt(match.group(1));
                display = desk = -1;
                tasks = null;
            } else if ((match = DISPLAY.matcher(text)).matches()) {
                display = Integer.parseInt(match.group(1));
                desk = -1;
                tasks = null;
            } else if ((match = DESK.matcher(text)).matches()) {
                desk = Integer.parseInt(match.group(1));
                tasks = null;
            } else if ((match = TASKS.matcher(text)).matches()) {
                tasks = new HashSet<>();
                if (!match.group(1).isBlank()) {
                    for (String task : match.group(1).split(",")) {
                        tasks.add(Integer.parseInt(task.trim()));
                    }
                }
            } else if (text.equals("visibleTasks=[]") && user == userId && display == 0
                    && desk >= 0 && tasks != null && !tasks.isEmpty()) {
                result.put(desk, tasks);
            }
        }
        return result;
    }
}
