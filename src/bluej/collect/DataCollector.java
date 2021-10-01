/*
 This file is part of the BlueJ program. 
 Copyright (C) 2012,2013  Michael Kolling and John Rosenberg 
 
 This program is free software; you can redistribute it and/or 
 modify it under the terms of the GNU General Public License 
 as published by the Free Software Foundation; either version 2 
 of the License, or (at your option) any later version. 
 
 This program is distributed in the hope that it will be useful, 
 but WITHOUT ANY WARRANTY; without even the implied warranty of 
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 GNU General Public License for more details. 
 
 You should have received a copy of the GNU General Public License 
 along with this program; if not, write to the Free Software 
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA. 
 
 This file is subject to the Classpath exception as provided in the  
 LICENSE.txt file that accompanied this code.
 */
package bluej.collect;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.http.entity.mime.MultipartEntity;

import difflib.Delta;
import difflib.DiffUtils;
import difflib.Patch;

import bluej.Config;
import bluej.compiler.Diagnostic;
import bluej.debugger.DebuggerTestResult;
import bluej.debugger.ExceptionDescription;
import bluej.debugger.SourceLocation;
import bluej.debugmgr.inspector.ClassInspector;
import bluej.debugmgr.inspector.Inspector;
import bluej.debugmgr.inspector.ObjectInspector;
import bluej.extmgr.ExtensionWrapper;
import bluej.groupwork.Repository;
import bluej.pkgmgr.Project;
import bluej.pkgmgr.Package;
import bluej.pkgmgr.target.ClassTarget;
import bluej.utility.Utility;

/**
 * DataCollector for sending off data.
 * 
 * You can call these methods under any setting. It is this class's responsibility to check:
 *  - That the user has actually opted in
 *  - That we're not running Greenfoot
 *  - That we don't keep attempting to send when there's no net connection (actually, DataSubmitter checks this for us)
 */
public class DataCollector
{
    private static final String PROPERTY_UUID = "blackbox.uuid";
    private static final String PROPERTY_EXPERIMENT = "blackbox.experiment";
    private static final String PROPERTY_PARTICIPANT = "blackbox.participant";
    private static final String OPT_OUT = "optout";
    
    /**
     * We decide at the very beginning of the session whether we are recording, based
     * on whether the user was opted in.  Starting to record mid-session is fairly
     * useless, so even if the user opts in during the session, we won't record
     * their data until the next session begins.  Thus, this variable
     * will never become true after startSession() has been called, althoug
     * it may become false if the user opts out mid-session
     */
    private static boolean recordingThisSession;
    /**
     * Session identifier.  Never changes after startSession() has been called:
     */
    private static String sessionUuid;
    
    /**
     * These three variables can change during the execution:
     */
    private static String uuid;
    private static String experimentIdentifier;
    private static String participantIdentifier;
    
    /**
     * In BlueJ, the Project holds the list of inspectors, even though really
     * an inspector can be traced back to being per-package.  We hold this map
     * to record this association (which we know when an inspector is shown)
     * so that we can re-use it when the inspector is hidden. 
     */
    private static IdentityHashMap<Inspector, Package> inspectorPackages = new IdentityHashMap<Inspector, Package>();

    /**
     * Checks whether we should send data.  This takes into account whether we
     * are in Greenfoot, and opt-in status.  It doesn't check whether we have stopped
     * sending due to connection problems -- DataSubmitter keeps track of that.
     */
    private static synchronized boolean dontSend()
    {
        return Config.isGreenfoot() || !recordingThisSession;
    }
    
    /**
     * Checks if the user's UUID would be valid for recording, which is another way
     * of asking if the user has opted in.  However, this is not the same as "are we currently
     * sending data for this user", because if they opt-in mid-session, this method
     * will return true, but dontSend() will also return true (because recordingThisSession
     * will be false; it is set once at the very beginning of the session).
     */
    private static synchronized boolean uuidValidForRecording()
    {
        return uuid != null && !(OPT_OUT.equals(uuid)) && uuid.length() >= 32;
    }

    private static synchronized void startSession()
    {
        // Look for an existing UUID:
        uuid = Config.getPropString(PROPERTY_UUID, null);
        
        // If there is no UUID in the file, or it's invalid, ask them if they want to opt in or opt out:
        if (!(OPT_OUT.equals(uuid)) && !uuidValidForRecording() )
        {
            changeOptInOut();
        }
        
        recordingThisSession = uuidValidForRecording();
        
        if (recordingThisSession)
        {
            // Initialise the session:
            DataSubmitter.initSequence();
            sessionUuid = UUID.randomUUID().toString();
        }
        
        // We fetch these regardless, so that everything is consistent
        // if the user opts in and edits them mid-session:
        experimentIdentifier = Config.getPropString(PROPERTY_EXPERIMENT, null);
        participantIdentifier = Config.getPropString(PROPERTY_PARTICIPANT, null);
    }
    
    /**
     * Gets the user's UUID
     */
    public static synchronized String getUserID()
    {
        return uuid;
    }

    /**
     * Gets a String to display to the user in the preferences, explaining their
     * current opt-in/recording status
     */
    public static synchronized String getOptInOutStatus()
    {
        if (recordingThisSession)
        {
            return Config.getString("collect.status.optedin");
        }
        else if (uuidValidForRecording())
        {
            return Config.getString("collect.status.nextsession");
        }
        else
        {
            return Config.getString("collect.status.optedout");
        }
    }

    /**
     * Show a dialog to ask the user for their opt-in/opt-out preference,
     * and then update the UUID accordingly
     */
    public static synchronized void changeOptInOut()
    {
        DataCollectionDialog dlg = new DataCollectionDialog();
        dlg.setLocationRelativeTo(null); // Centre on screen
        dlg.setVisible(true);
        
        if (dlg.optedIn())
        {
            // Only generate new UUID if didn't have one already:
            if (!uuidValidForRecording())
            {
                uuid = UUID.randomUUID().toString();
            }
        }
        else
        {
            uuid = OPT_OUT;
            recordingThisSession = false;
        }
        Config.putPropString(PROPERTY_UUID, uuid);
    }

    /**
     * Submits an event with no extra data.  A useful short-hand for calling submitEvent
     * with no content in the event.
     */
    private static void submitEventNoData(Project project, Package pkg, EventName eventName)
    {
        if (dontSend()) return;
        submitEvent(project, pkg, eventName, new PlainEvent(new MultipartEntity()));
    }
    
    /**
     * Submits an event and adds a source location.  This should be used if the file
     * is within the project (otherwise see submitEventWithClassLocation)
     * 
     * @param project
     * @param eventName
     * @param mpe You can pass null if you have no other data to give
     * @param sourceFile
     * @param lineNumber
     */
    private static void submitEventWithLocalLocation(Project project, Package pkg, EventName eventName, MultipartEntity mpe, File sourceFile, int lineNumber)
    {
        if (mpe == null)
        {
            mpe = new MultipartEntity();
        }
        
        mpe.addPart("event[source_file_name]", CollectUtility.toBodyLocal(project, sourceFile));
        mpe.addPart("event[line_number]", CollectUtility.toBody(lineNumber));
        
        submitEvent(project, pkg, eventName, new PlainEvent(mpe));
    }


    /**
     * Submits an event and adds a source location.  This should be used if it is not
     * known whether the class is in the project, or whether it might be from a library
     * (e.g. java.io.*) or wherever.
     * 
     * @param project
     * @param eventName
     * @param mpe You can pass null if you have no other data to give
     * @param classSourceName
     * @param lineNumber
     */
    private static void submitDebuggerEventWithLocation(Project project, EventName eventName, MultipartEntity mpe, SourceLocation[] stack)
    {
        if (mpe == null)
        {
            mpe = new MultipartEntity();
        }
        
        DataCollector.addStackTrace(mpe, "event[stack]", stack);
        
        submitEvent(project, null, eventName, new PlainEvent(mpe));
    }


    private static synchronized void submitEvent(final Project project, final Package pkg, final EventName eventName, final Event evt)
    {
        if (dontSend()) return;
        
        final String projectName = project == null ? null : project.getProjectName();
        final String projectPathHash = project == null ? null : CollectUtility.md5Hash(project.getProjectDir().getAbsolutePath());
        final String packageName = pkg == null ? null : pkg.getQualifiedName();
        
        // We take a copy of these internal variables, so that we don't have a race hazard
        // if the variable changes between now and the event being sent:
        final String uuidCopy = uuid;
        final String experimentCopy = experimentIdentifier;
        final String participantCopy = participantIdentifier;
        
        /**
         * Wrap the Event we've been given to add the other normal expected fields:
         */
        DataSubmitter.submitEvent(new Event() {
            
            @Override public void success(Map<FileKey, List<String>> fileVersions)
            {
                evt.success(fileVersions);
            }
            
            @Override
            public MultipartEntity makeData(int sequenceNum, Map<FileKey, List<String>> fileVersions)
            {
                MultipartEntity mpe = evt.makeData(sequenceNum, fileVersions);
                
                if (mpe == null)
                    return null;
                
                mpe.addPart("user[uuid]", CollectUtility.toBody(uuidCopy));
                mpe.addPart("session[id]", CollectUtility.toBody(sessionUuid));
                mpe.addPart("participant[experiment]", CollectUtility.toBody(experimentCopy));
                mpe.addPart("participant[participant]", CollectUtility.toBody(participantCopy));
                
                if (projectName != null)
                {
                    mpe.addPart("project[name]", CollectUtility.toBody(projectName));
                    mpe.addPart("project[path_hash]", CollectUtility.toBody(projectPathHash));
                    
                    if (packageName != null)
                    {
                        mpe.addPart("package[name]", CollectUtility.toBody(packageName));
                    }
                }
                
                mpe.addPart("event[source_time]", CollectUtility.toBody(DateFormat.getDateTimeInstance().format(new Date())));
                mpe.addPart("event[name]", CollectUtility.toBody(eventName.getName()));
                mpe.addPart("event[sequence_id]", CollectUtility.toBody(Integer.toString(sequenceNum)));
                
                return mpe;
            }
        });
    }

    public static void compiled(Project proj, Package pkg, File[] sources, List<DiagnosticWithShown> diagnostics, boolean success)
    {
        if (dontSend()) return;
        
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[compile_success]", CollectUtility.toBody(success));
        
        for (File src : sources)
        {
            mpe.addPart("event[compile_input][][source_file_name]", CollectUtility.toBody(CollectUtility.toPath(proj, src)));
        }        
        
        for (DiagnosticWithShown dws : diagnostics)
        {
            final Diagnostic d = dws.getDiagnostic();
            
            mpe.addPart("event[compile_output][][is_error]", CollectUtility.toBody(d.getType() == Diagnostic.ERROR));
            mpe.addPart("event[compile_output][][shown]", CollectUtility.toBody(dws.wasShownToUser()));
            mpe.addPart("event[compile_output][][message]", CollectUtility.toBody(d.getMessage()));
            if (d.getFileName() != null)
            {
                mpe.addPart("event[compile_output][][start_line]", CollectUtility.toBody(d.getStartLine()));
                mpe.addPart("event[compile_output][][end_line]", CollectUtility.toBody(d.getEndLine()));
                mpe.addPart("event[compile_output][][start_column]", CollectUtility.toBody(d.getStartColumn()));
                mpe.addPart("event[compile_output][][end_column]", CollectUtility.toBody(d.getEndColumn()));
                // Must make file name relative for anonymisation:
                String relative = CollectUtility.toPath(proj, new File(d.getFileName()));
                mpe.addPart("event[compile_output][][source_file_name]", CollectUtility.toBody(relative));
            }
        }
        submitEvent(proj, pkg, EventName.COMPILE, new PlainEvent(mpe));
    }
    
    public static void bluejOpened(String osVersion, String javaVersion, String bluejVersion, String interfaceLanguage, List<ExtensionWrapper> extensions)
    {
        if (Config.isGreenfoot()) return; //Don't even look for UUID
        startSession();
        
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("installation[operating_system]", CollectUtility.toBody(osVersion));
        mpe.addPart("installation[java_version]", CollectUtility.toBody(javaVersion));
        mpe.addPart("installation[bluej_version]", CollectUtility.toBody(bluejVersion));
        mpe.addPart("installation[interface_language]", CollectUtility.toBody(interfaceLanguage));
        
        addExtensions(mpe, extensions);
        
        submitEvent(null, null, EventName.BLUEJ_START, new PlainEvent(mpe));
    }


    private static void addExtensions(MultipartEntity mpe,
            List<ExtensionWrapper> extensions)
    {
        for (ExtensionWrapper ext : extensions)
        {
            mpe.addPart("extensions[][name]", CollectUtility.toBody(ext.safeGetExtensionName()));
            mpe.addPart("extensions[][version]", CollectUtility.toBody(ext.safeGetExtensionVersion()));
        }
    }
    
    public static void projectOpened(Project proj, List<ExtensionWrapper> projectExtensions)
    {
        if (dontSend()) {
            return;
        }
        
        MultipartEntity mpe = new MultipartEntity();
        
        addExtensions(mpe, projectExtensions);
        
        submitEventNoData(proj, null, EventName.PROJECT_OPENING);
    }
    
    public static void projectClosed(Project proj)
    {
        submitEventNoData(proj, null, EventName.PROJECT_CLOSING);
    }
    
    public static void packageOpened(Package pkg)
    {
        if (dontSend())
            return;
        
        final Project proj = pkg.getProject();
        
        final MultipartEntity mpe = new MultipartEntity();
        
        final Map<FileKey, List<String>> versions = new HashMap<FileKey, List<String>>();
        
        for (ClassTarget ct : pkg.getClassTargets())
        {

            String relative = CollectUtility.toPath(proj, ct.getSourceFile());
            
            mpe.addPart("project[source_files][][name]", CollectUtility.toBody(relative));
            
            String anonymisedContent = CollectUtility.readFileAndAnonymise(proj, ct.getSourceFile());
            
            if (anonymisedContent != null)
            {
                mpe.addPart("source_histories[][source_history_type]", CollectUtility.toBody("complete"));
                mpe.addPart("source_histories[][name]", CollectUtility.toBody(relative));
                mpe.addPart("source_histories[][content]", CollectUtility.toBody(anonymisedContent));
                versions.put(new FileKey(proj, relative), Arrays.asList(Utility.splitLines(anonymisedContent)));
            }
        }
        
        submitEvent(proj, pkg, EventName.PACKAGE_OPENING, new Event() {
            
            @Override
            public void success(Map<FileKey, List<String>> fileVersions)
            {
                fileVersions.putAll(versions);                
            }
            
            @Override
            public MultipartEntity makeData(int sequenceNum, Map<FileKey, List<String>> fileVersions)
            {
                return mpe;
            }
        });
    }
    
    public static void packageClosed(Package pkg)
    {
        submitEventNoData(pkg.getProject(), pkg, EventName.PACKAGE_CLOSING);
    }

    public static void bluejClosed()
    {
        submitEventNoData(null, null, EventName.BLUEJ_FINISH);
        
        // Give the queue 1 second to be flushed, so that the finish event gets time to be sent,
        // but otherwise, return anyway (can't wait forever):
        DataSubmitter.waitForQueueFlush(1000);
    }

    public static void restartVM(Project project)
    {
        submitEventNoData(project, null, EventName.RESETTING_VM);        
    }

    public static void edit(final Package pkg, final File path, final String source, final boolean includeOneLineEdits)
    {
        if (dontSend())
            return;
        
        final Project proj = pkg.getProject();
        final FileKey key = new FileKey(proj, CollectUtility.toPath(proj, path));
        final String anonSource = CodeAnonymiser.anonymise(source);
        final List<String> anonDoc = Arrays.asList(Utility.splitLines(anonSource));
                
        submitEvent(proj, pkg, EventName.EDIT, new Event() {

            private boolean dontReplace = false;
            
            //Edit solely within one line
            private boolean isOneLineDiff(Patch patch)
            {
                if (patch.getDeltas().size() > 1)
                    return false;
                Delta theDelta = patch.getDeltas().get(0);
                return theDelta.getOriginal().size() == 1 && theDelta.getRevised().size() == 1;
            }
            
            @Override
            public MultipartEntity makeData(int sequenceNum, Map<FileKey, List<String>> fileVersions)
            {
                List<String> previousDoc = fileVersions.get(key);
                if (previousDoc == null)
                    previousDoc = new ArrayList<String>(); // Diff against empty file
                
                MultipartEntity mpe = new MultipartEntity();
                
                Patch patch = DiffUtils.diff(previousDoc, anonDoc);
                
                if (patch.getDeltas().isEmpty() || (isOneLineDiff(patch) && !includeOneLineEdits))
                {
                    dontReplace = true;
                    return null;
                }
                
                String diff = makeDiff(patch);
                
                mpe.addPart("source_histories[][content]", CollectUtility.toBody(diff));
                mpe.addPart("source_histories[][source_history_type]", CollectUtility.toBody("diff"));
                mpe.addPart("source_histories[][name]", CollectUtility.toBody(CollectUtility.toPath(proj, path))); 
                
                return mpe;
            }

            @Override
            public void success(Map<FileKey, List<String>> fileVersions)
            {
                if (!dontReplace)
                {
                    fileVersions.put(key, anonDoc);
                }
            }
        });
    }
    
    @SuppressWarnings("unchecked")
    // protected for testing purposes
    protected static String makeDiff(Patch patch)
    {
        StringBuilder diff = new StringBuilder();
        // There is a DiffUtils.generateUnifiedDiff function, but don't use it
        // It's broken in 1.2.1 for patches that purely add lines
        
        for (Delta delta: patch.getDeltas()) {
            int srcLine, srcSize, destLine, destSize;
            srcSize = delta.getOriginal().size();
            destSize = delta.getRevised().size();
            // It seems that the line numbers given back when the patch is a pure-insert
            // (i.e. zero lines are modified in the original file) are off by one, so
            // correct for this:
            if (srcSize > 0)
            {
                srcLine = delta.getOriginal().getPosition() + 1;
                destLine = delta.getRevised().getPosition() + 1;
            }
            else
            {
                srcLine = delta.getOriginal().getPosition();
                destLine = delta.getRevised().getPosition();
            }
            diff.append("@@ -" + srcLine + "," + srcSize + " +" + destLine + "," + destSize + " @@\n");
            for (String l : (List<String>)delta.getOriginal().getLines())
            {
                diff.append("-" + l + "\n");
            }
            for (String l : (List<String>)delta.getRevised().getLines())
            {
                diff.append("+" + l + "\n");
            }
        }
        return diff.toString();
    }
    
    
    public static void debuggerTerminate(Project project)
    {
        submitEventNoData(project, null, EventName.DEBUGGER_TERMINATE);        
    }
    
    public static void debuggerChangeVisible(Project project, boolean newVis)
    {
        submitEventNoData(project, null, newVis ? EventName.DEBUGGER_OPEN : EventName.DEBUGGER_CLOSE);        
    }
    
    public static void debuggerBreakpointToggle(Package pkg, File sourceFile, int lineNumber, boolean newState)
    {
        if (dontSend()) {
            return;
        }

        submitEventWithLocalLocation(pkg.getProject(), pkg, newState ? EventName.DEBUGGER_BREAKPOINT_ADD : EventName.DEBUGGER_BREAKPOINT_REMOVE, null, sourceFile, lineNumber);
    }
    
    public static void debuggerContinue(Project project, String threadName)
    {
        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[thread_name]", CollectUtility.toBody(threadName));
        submitEvent(project, null, EventName.DEBUGGER_CONTINUE, new PlainEvent(mpe));        
    }

    public static void debuggerHalt(Project project, String threadName, SourceLocation[] stack)
    {
        if (dontSend()) {
            return;
        }

        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[thread_name]", CollectUtility.toBody(threadName));
        submitDebuggerEventWithLocation(project, EventName.DEBUGGER_HALT, mpe, stack);
    }
    
    public static void debuggerStepInto(Project project, String threadName, SourceLocation[] stack)
    {
        if (dontSend()) {
            return;
        }

        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[thread_name]", CollectUtility.toBody(threadName));
        submitDebuggerEventWithLocation(project, EventName.DEBUGGER_STEP_INTO, mpe, stack);
    }
    
    public static void debuggerStepOver(Project project, String threadName, SourceLocation[] stack)
    {
        if (dontSend()) {
            return;
        }

        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[thread_name]", CollectUtility.toBody(threadName));
        submitDebuggerEventWithLocation(project, EventName.DEBUGGER_STEP_OVER, mpe, stack);
    }
    
    public static void debuggerHitBreakpoint(Project project, String threadName, SourceLocation[] stack)
    {
        if (dontSend()) {
            return;
        }

        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[thread_name]", CollectUtility.toBody(threadName));
        submitDebuggerEventWithLocation(project, EventName.DEBUGGER_HIT_BREAKPOINT, mpe, stack);
    }
    
    public static void codePadSuccess(Package pkg, String command, String output)
    {
        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[codepad][outcome]", CollectUtility.toBody("success"));
        mpe.addPart("event[codepad][command]", CollectUtility.toBody(command));
        mpe.addPart("event[codepad][result]", CollectUtility.toBody(output));
        submitEvent(pkg.getProject(), pkg, EventName.CODEPAD, new PlainEvent(mpe));
    }
    
    public static void codePadError(Package pkg, String command, String error)
    {
        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[codepad][outcome]", CollectUtility.toBody("error"));
        mpe.addPart("event[codepad][command]", CollectUtility.toBody(command));
        mpe.addPart("event[codepad][error]", CollectUtility.toBody(error));
        submitEvent(pkg.getProject(), pkg, EventName.CODEPAD, new PlainEvent(mpe));
    }
    
    public static void codePadException(Package pkg, String command, String exception)
    {
        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[codepad][outcome]", CollectUtility.toBody("exception"));
        mpe.addPart("event[codepad][command]", CollectUtility.toBody(command));
        mpe.addPart("event[codepad][exception]", CollectUtility.toBody(exception));
        submitEvent(pkg.getProject(), pkg, EventName.CODEPAD, new PlainEvent(mpe));
    }


    public static void renamedClass(Package pkg, File oldSourceFile, File newSourceFile)
    {
        if (dontSend()) {
            return;
        }

        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("source_histories[][source_history_type]", CollectUtility.toBody("rename"));
        mpe.addPart("source_histories[][content]", CollectUtility.toBodyLocal(pkg.getProject(), oldSourceFile));
        mpe.addPart("source_histories[][name]", CollectUtility.toBodyLocal(pkg.getProject(), newSourceFile));
        submitEvent(pkg.getProject(), pkg, EventName.RENAME, new PlainEvent(mpe));
    }
    
    public static void removeClass(Package pkg, File sourceFile)
    {
        if (dontSend()) {
            return;
        }
        
        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("source_histories[][source_history_type]", CollectUtility.toBody("file_delete"));
        mpe.addPart("source_histories[][name]", CollectUtility.toBodyLocal(pkg.getProject(), sourceFile));
        submitEvent(pkg.getProject(), pkg, EventName.DELETE, new PlainEvent(mpe));
    }
    
    public static void addClass(Package pkg, File sourceFile)
    {
        if (dontSend()) {
            return;
        }
        
        final MultipartEntity mpe = new MultipartEntity();
        final Project project = pkg.getProject();
        
        final String contents = CollectUtility.readFileAndAnonymise(project, sourceFile);
        
        mpe.addPart("project[source_files][][name]", CollectUtility.toBodyLocal(project, sourceFile));
        mpe.addPart("source_histories[][source_history_type]", CollectUtility.toBody("complete"));
        mpe.addPart("source_histories[][name]", CollectUtility.toBodyLocal(project, sourceFile));
        mpe.addPart("source_histories[][content]", CollectUtility.toBody(contents));
        final FileKey key = new FileKey(project, CollectUtility.toPath(project, sourceFile));
        
        submitEvent(project, pkg, EventName.ADD, new Event() {
            
            @Override
            public void success(Map<FileKey, List<String>> fileVersions)
            {
                fileVersions.put(key, Arrays.asList(Utility.splitLines(contents)));
            }
            
            @Override
            public MultipartEntity makeData(int sequenceNum, Map<FileKey, List<String>> fileVersions)
            {
                return mpe;
            }
        });
    }
    
    public static void teamShareProject(Project project, Repository repo)
    {
        submitEvent(project, null, EventName.VCS_SHARE, new PlainEvent(DataCollector.getRepoMPE(repo)));    
    }
    
    public static void teamCommitProject(Project project, Repository repo, Collection<File> committedFiles)
    {
        MultipartEntity mpe = DataCollector.getRepoMPE(repo);
        for (File f : committedFiles)
        {
            mpe.addPart("vcs_files[][file]", CollectUtility.toBodyLocal(project, f));
        }
        submitEvent(project, null, EventName.VCS_COMMIT, new PlainEvent(mpe));
    }
    
    public static void teamUpdateProject(Project project, Repository repo, Collection<File> updatedFiles)
    {
        MultipartEntity mpe = DataCollector.getRepoMPE(repo);
        for (File f : updatedFiles)
        {
            mpe.addPart("vcs_files[][file]", CollectUtility.toBodyLocal(project, f));
        }
        submitEvent(project, null, EventName.VCS_UPDATE, new PlainEvent(mpe));
    }
    
    public static void teamStatusProject(Project project, Repository repo, Map<File, String> status)
    {
        MultipartEntity mpe = DataCollector.getRepoMPE(repo);
        for (Map.Entry<File, String> s : status.entrySet())
        {
            mpe.addPart("vcs_files[][file]", CollectUtility.toBodyLocal(project, s.getKey()));
            mpe.addPart("vcs_files[][status]", CollectUtility.toBody(s.getValue()));
        }
        submitEvent(project, null, EventName.VCS_STATUS, new PlainEvent(mpe));
    }
    
    public static void teamHistoryProject(Project project, Repository repo)
    {
        submitEvent(project, null, EventName.VCS_HISTORY, new PlainEvent(DataCollector.getRepoMPE(repo)));    
    }


    public static void showHideTerminal(Project project, boolean show)
    {
        submitEventNoData(project, null, show ? EventName.TERMINAL_OPEN : EventName.TERMINAL_CLOSE);      
    }

    
    public static void invokeCompileError(Package pkg, String code, String compilationError)
    {
        if (! dontSend()) {
            MultipartEntity mpe = new MultipartEntity();

            mpe.addPart("event[invoke][code]", CollectUtility.toBody(code));
            mpe.addPart("event[invoke][result]", CollectUtility.toBody("compile_error"));
            mpe.addPart("event[invoke][compile_error]", CollectUtility.toBody(compilationError));
            submitEvent(pkg.getProject(), pkg, EventName.INVOKE_METHOD, new PlainEvent(mpe));
        }
    }
    
    public static void invokeMethodSuccess(Package pkg, String code, String objName, String typeName, int testIdentifier, int invocationIdentifier)
    {
        if (! dontSend()) {
            MultipartEntity mpe = new MultipartEntity();

            mpe.addPart("event[invoke][code]", CollectUtility.toBody(code));
            mpe.addPart("event[invoke][type_name]", CollectUtility.toBody(typeName));
            mpe.addPart("event[invoke][result]", CollectUtility.toBody("success"));
            mpe.addPart("event[invoke][test_identifier]", CollectUtility.toBody(testIdentifier));
            mpe.addPart("event[invoke][invoke_identifier]", CollectUtility.toBody(invocationIdentifier));
            if (objName != null)
            {
                mpe.addPart("event[invoke][bench_object][class_name]", CollectUtility.toBody(typeName));
                mpe.addPart("event[invoke][bench_object][name]", CollectUtility.toBody(objName));
            }
            submitEvent(pkg.getProject(), pkg, EventName.INVOKE_METHOD, new PlainEvent(mpe));
        }
    }
    
    public static void invokeMethodException(Package pkg, String code, ExceptionDescription ed)
    {
        if (! dontSend()) {
            MultipartEntity mpe = new MultipartEntity();

            mpe.addPart("event[invoke][code]", CollectUtility.toBody(code));
            mpe.addPart("event[invoke][result]", CollectUtility.toBody("exception"));
            mpe.addPart("event[invoke][exception_class]", CollectUtility.toBody(ed.getClassName()));
            mpe.addPart("event[invoke][exception_message]", CollectUtility.toBody(ed.getText()));
            DataCollector.addStackTrace(mpe, "event[invoke][exception_stack]", ed.getStack().toArray(new SourceLocation[0]));
            submitEvent(pkg.getProject(), pkg, EventName.INVOKE_METHOD, new PlainEvent(mpe));
        }
    }
    
    public static void invokeMethodTerminated(Package pkg, String code)
    {
        if (! dontSend()) {
            MultipartEntity mpe = new MultipartEntity();
            mpe.addPart("event[invoke][code]", CollectUtility.toBody(code));
            mpe.addPart("event[invoke][result]", CollectUtility.toBody("terminated"));
            submitEvent(pkg.getProject(), pkg, EventName.INVOKE_METHOD, new PlainEvent(mpe));
        }
    }
    
    public static void removeObject(Package pkg, String name)
    {
        if (! dontSend()) {
            MultipartEntity mpe = new MultipartEntity();
            mpe.addPart("event[object_name]", CollectUtility.toBody(name));
            submitEvent(pkg.getProject(), pkg, EventName.REMOVE_OBJECT, new PlainEvent(mpe));
        }
    }


    public static void inspectorClassShow(Package pkg, Inspector inspector, String className)
    {
        if (! dontSend()) {
            MultipartEntity mpe = new MultipartEntity();
            mpe.addPart("event[inspect][unique]", CollectUtility.toBody(inspector.getUniqueId()));
            mpe.addPart("event[inspect][static_class]", CollectUtility.toBody(className));
            inspectorPackages.put(inspector, pkg);
            submitEvent(pkg.getProject(), pkg, EventName.INSPECTOR_SHOW, new PlainEvent(mpe));
        }
    }
    
    public static void inspectorObjectShow(Package pkg, Inspector inspector, String benchName, String className, String displayName)
    {
        if (! dontSend()) {
            MultipartEntity mpe = new MultipartEntity();
            mpe.addPart("event[inspect][unique]", CollectUtility.toBody(inspector.getUniqueId()));
            mpe.addPart("event[inspect][display_name]", CollectUtility.toBody(displayName));
            mpe.addPart("event[inspect][class_name]", CollectUtility.toBody(className));
            if (benchName != null)
            {
                mpe.addPart("event[inspect][bench_object_name]", CollectUtility.toBody(benchName));
            }
            inspectorPackages.put(inspector, pkg);
            submitEvent(pkg.getProject(), pkg, EventName.INSPECTOR_SHOW, new PlainEvent(mpe));
        }
    }
    
    public static void inspectorHide(Project project, Inspector inspector)
    {
        if (! dontSend()) {
            MultipartEntity mpe = new MultipartEntity();
            mpe.addPart("event[inspect][unique]", CollectUtility.toBody(inspector.getUniqueId()));
            if (inspector instanceof ClassInspector || inspector instanceof ObjectInspector)
            {
                submitEvent(project, inspectorPackages.get(project), EventName.INSPECTOR_HIDE, new PlainEvent(mpe));
            }
        }
    }


    public static void benchGet(Package pkg, String benchName, String typeName, int testIdentifier)
    {
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[bench_object][class_name]", CollectUtility.toBody(typeName));
        mpe.addPart("event[bench_object][name]", CollectUtility.toBody(benchName));
        mpe.addPart("event[test_identifier]", CollectUtility.toBody(testIdentifier));
        submitEvent(pkg.getProject(), pkg, EventName.BENCH_GET, new PlainEvent(mpe));
        
    }

    public static void startTestMethod(Package pkg, int testIdentifier, File sourceFile, String testName)
    {
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[test][test_identifier]", CollectUtility.toBody(testIdentifier));
        mpe.addPart("event[test][source_file]", CollectUtility.toBodyLocal(pkg.getProject(), sourceFile));
        mpe.addPart("event[test][method_name]", CollectUtility.toBody(testName));
        
        submitEvent(pkg.getProject(), pkg, EventName.START_TEST, new PlainEvent(mpe));
        
    }

    public static void cancelTestMethod(Package pkg, int testIdentifier)
    {
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[test][test_identifier]", CollectUtility.toBody(testIdentifier));
        
        submitEvent(pkg.getProject(), pkg, EventName.CANCEL_TEST, new PlainEvent(mpe));
    }

    public static void endTestMethod(Package pkg, int testIdentifier)
    {
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[test][test_identifier]", CollectUtility.toBody(testIdentifier));
        
        submitEvent(pkg.getProject(), pkg, EventName.END_TEST, new PlainEvent(mpe));
        
    }

    public static void assertTestMethod(Package pkg, int testIdentifier, int invocationIdentifier, 
            String assertion, String param1, String param2)
    {
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[assert][test_identifier]", CollectUtility.toBody(testIdentifier));
        mpe.addPart("event[assert][invoke_identifier]", CollectUtility.toBody(invocationIdentifier));
        mpe.addPart("event[assert][assertion]", CollectUtility.toBody(assertion));
        mpe.addPart("event[assert][param1]", CollectUtility.toBody(param1));
        mpe.addPart("event[assert][param2]", CollectUtility.toBody(param2));
        
        submitEvent(pkg.getProject(), pkg, EventName.ASSERTION, new PlainEvent(mpe));
        
    }

    public static void objectBenchToFixture(Package pkg, File sourceFile, List<String> benchNames)
    {
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[source_file_name]", CollectUtility.toBodyLocal(pkg.getProject(), sourceFile));
        for (String name : benchNames)
        {
            mpe.addPart("event[bench_objects][][name]", CollectUtility.toBody(name));
        }        
        
        submitEvent(pkg.getProject(), pkg, EventName.BENCH_TO_FIXTURE, new PlainEvent(mpe));
        
    }
    
    public static class NamedTyped
    {
        private  String name;
        private  String type;
        
        public NamedTyped(String name, String type)
        {
            this.name = name;
            this.type = type;
        }
        public String getName()
        {
            return name;
        }
        public String getType()
        {
            return type;
        }
        
        
    };
    
    public static void fixtureToObjectBench(Package pkg, File sourceFile, List<NamedTyped> objects)
    {
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[source_file_name]", CollectUtility.toBodyLocal(pkg.getProject(), sourceFile));
        for (NamedTyped obj : objects)
        {
            mpe.addPart("event[bench_objects][][name]", CollectUtility.toBody(obj.getName()));
            mpe.addPart("event[bench_objects][][class_name]", CollectUtility.toBody(obj.getType()));
        }        
        
        submitEvent(pkg.getProject(), pkg, EventName.FIXTURE_TO_BENCH, new PlainEvent(mpe));
    }

    public static void testResult(Package pkg, DebuggerTestResult lastResult)
    {
        MultipartEntity mpe = new MultipartEntity();
        
        mpe.addPart("event[class_name]", CollectUtility.toBody(lastResult.getQualifiedClassName()));
        mpe.addPart("event[method_name]", CollectUtility.toBody(lastResult.getMethodName()));
        mpe.addPart("event[run_time]", CollectUtility.toBody(lastResult.getRunTimeMs()));
        String status = "unknown";
        if (lastResult.isSuccess())
            status = "success";
        else if (lastResult.isFailure())
            status = "failure";
        else if (lastResult.isError())
            status = "error";
        mpe.addPart("event[result]", CollectUtility.toBody(status));
        
        if (!lastResult.isSuccess())
        {
            mpe.addPart("event[exception_message]", CollectUtility.toBody(lastResult.getExceptionMessage()));
            mpe.addPart("event[exception_trace]", CollectUtility.toBody(lastResult.getTrace()));
        }
        
        submitEvent(pkg.getProject(), pkg, EventName.RUN_TEST, new PlainEvent(mpe));
    }

    /**
     * Adds the given stack trace to the MPE, using the given list name.
     */
    private static void addStackTrace(MultipartEntity mpe, String listName, SourceLocation[] stack)
    {
        for (int i = 0; i < stack.length; i++)
        {
            mpe.addPart(listName + "[][entry]", CollectUtility.toBody(i));
            mpe.addPart(listName + "[][class_name]", CollectUtility.toBody(stack[i].getClassName()));
            mpe.addPart(listName + "[][class_source_name]", CollectUtility.toBody(stack[i].getFileName()));
            mpe.addPart(listName + "[][line_number]", CollectUtility.toBody(stack[i].getLineNumber()));
        }
    }

    /**
     * Turns the Repository into an MPE with appropriate information
     */
    private static MultipartEntity getRepoMPE(Repository repo)
    {
        MultipartEntity mpe = new MultipartEntity();
        mpe.addPart("event[vcs][vcs_type]", CollectUtility.toBody(repo.getVCSType()));
        mpe.addPart("event[vcs][protocol]", CollectUtility.toBody(repo.getVCSProtocol()));
        return mpe;
    }

    public static String getExperimentIdentifier()
    {
        return experimentIdentifier;
    }

    public static synchronized void setExperimentIdentifier(String experimentIdentifier)
    {
        DataCollector.experimentIdentifier = experimentIdentifier;
        Config.putPropString(PROPERTY_EXPERIMENT, experimentIdentifier);
    }

    public static String getParticipantIdentifier()
    {
        return participantIdentifier;
    }

    public static synchronized void setParticipantIdentifier(String participantIdentifier)
    {
        DataCollector.participantIdentifier = participantIdentifier;
        Config.putPropString(PROPERTY_PARTICIPANT, participantIdentifier);
    }

}