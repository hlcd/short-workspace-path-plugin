/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.shortwspath;

import hudson.Extension;
import hudson.FilePath;
import hudson.Functions;
import hudson.Util;
import hudson.model.TopLevelItem;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.slaves.WorkspaceLocator;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Extension
@Restricted(NoExternalUse.class)
public class ShortWsLocator extends WorkspaceLocator {
    private static final Logger LOGGER = Logger.getLogger(ShortWsLocator.class.getName());
    /**
     * The path length that should be available in workspace.
     *
     * The number is not supposed to include the path to the workspace itself.
     */
    private static int BUILD_PATH_LENGTH = Integer.getInteger("org.jenkinsci.plugins.shortwspath.BUILD_PATH_LENGTH", 512);
    private static boolean FORCE_SHORT_WS = true;
    private static boolean FORCE_MASTER = true;

    // To be invalidated when slave is reconnected
    private final Map<Node, Integer> cachedMaxLengths = new WeakHashMap<Node, Integer>();

    @Override
    public FilePath locate(TopLevelItem item, Node node) {
        LOGGER.info(String.format("locate item %s at node %s. force_master=%s force_short_ws=%s", item, node, FORCE_MASTER, FORCE_SHORT_WS));

        if (!(node instanceof Slave) && !FORCE_MASTER) {
            LOGGER.info("ignoring master node");
            return null;
        }

        String itemName = StringUtils.abbreviate(item.getName(), 0, 16);
        
        FilePath def = getDefaultPath(item, node);
        if (def == null) {
            LOGGER.info("default path == null. No idea what the path is going to look like - do not touch");
            return null; // No idea what the path is going to look like - do not touch
        }
        LOGGER.info(String.format("default path == %s", def));

        int usabeSpace = getUsableSpace(def, node);
        if (usabeSpace > BUILD_PATH_LENGTH && !FORCE_SHORT_WS) { 
            LOGGER.info(String.format("usable space (%d) > BUILD_PATH_LENGTH (%d)", usabeSpace, BUILD_PATH_LENGTH));
            return null; // There is plenty of room
        }

        
        // Replace the ellipsis with dashes to avoid problems with msbuild
        // prior to version 4.6.2.  It used its own path normalization (vs. built in .NET)
        // which doesn't recognize ... as a valid path.
        itemName = itemName.replace("...", "_");
        final String digest = Util.getDigestOf(item.getFullName()).substring(0, 8);
        FilePath workspaceRoot = getWorkspaceRoot(item, node);
        if (workspaceRoot == null) {
            LOGGER.info("no workspace root found");
            return null; // Slave went offline
        }
        FilePath newPath = workspaceRoot.child(itemName + digest);

        if (newPath.getRemote().length() < def.getRemote().length() || FORCE_SHORT_WS) {
            LOGGER.info(String.format("returning shortened path: %s", newPath));
            return newPath;
        } else {
            LOGGER.info(String.format("shortened path: %s >= original path: %s", newPath, def));
            return null; // Do nothing if it will not improve the situation
        }
        
    }

    /**
     * Mimic core behaviour in deducing the path length.
     */
     
	private FilePath getWorkspaceRoot(TopLevelItem item, Node node) {
        if ((node instanceof Slave)) { 
            return getWorkspaceRoot(item, (Slave)node);
        } 
        else if ((node instanceof Jenkins)) { 
            FilePath rootPath = ((Jenkins)node).getRootPath();
            String ws = ((Jenkins)node).getRawWorkspaceDir();
            String newWs = "workspace\\" + item.getFullName().replace('/','_');
            LOGGER.info(String.format("rootpath=%s ws=%s",rootPath, newWs));
            return new FilePath(rootPath, newWs);
        }
        else {
            return node.getWorkspaceFor(item);
        }
	}

    private FilePath getWorkspaceRoot(TopLevelItem item, Slave slave) {
		return slave.getWorkspaceRoot();
	}
     
    private FilePath getDefaultPath(TopLevelItem item, Slave slave) {
        
        final String itemFullName = item.getFullName();

        FilePath wsroot = getWorkspaceRoot(item, slave);
        if (wsroot == null) return null; // Offline

        return wsroot.child(itemFullName);
    }
    
    private FilePath getDefaultPath(TopLevelItem item, Node node) {
        if ((node instanceof Slave)) { 
            return getDefaultPath(item, (Slave)node);
        } 
        else {
            final String itemFullName = item.getFullName();

            FilePath wsroot = getWorkspaceRoot(item, node);
            if (wsroot == null) return null; // Offline
            return wsroot.child(itemFullName);
        }
    }

    private int getUsableSpace(FilePath path, Node node) {
        Integer platformMax = cachedMaxLengths.get(node);
        if (platformMax == null) {
            final Sniffer sniffer = new Sniffer();
            try {
                platformMax = path.act(sniffer);
                cachedMaxLengths.put(node, platformMax);
            } catch (IOException ex) {
                LOGGER.log(Level.INFO, "Unalbe to " + sniffer, ex);
                return Integer.MAX_VALUE; // Do not intercept
            } catch (InterruptedException ex) {
                LOGGER.log(Level.INFO, "Interrupted while trying to " + sniffer, ex);
                return Integer.MAX_VALUE; // Do not intercept
            }
        }
        int prefixLength = path.getRemote().length();
        LOGGER.info(String.format("usable space=max(%d)-%d (%s)", platformMax, prefixLength, path.getRemote()));
        return platformMax - prefixLength;
    }

    private static final class Sniffer implements FilePath.FileCallable<Integer> {

        public Integer invoke(File ws, VirtualChannel channel) throws IOException, InterruptedException {
            // Good enough for now
            return Functions.isWindows()
                    ? 260 // Windows
                    : 4096 // Linux and hopefully other systems too
            ;
        }

        @Override
        public String toString() {
            return "discover max FS path length on node";
        }
    }
}
