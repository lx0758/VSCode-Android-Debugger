import { l10n } from 'vscode';

export const t = {
  activating: () => l10n.t('Activating Android Debugger'),
  activated: () => l10n.t('Activated'),
  deactivating: () => l10n.t('Deactivating Android Debugger'),

  jdkMissingLong: () => l10n.t('JDK 8+ not detected. Attach will fail. Please install JDK and set JAVA_HOME.'),
  jdkWarning: () => l10n.t('Android Debugger: JDK not detected. Commands are visible but will fail when run.'),
  installJdk: () => l10n.t('Install JDK'),
  viewOutput: () => l10n.t('View Output'),
  jdkDetected: (path: string, version: number) => l10n.t('JDK detected: {0} (v{1})', path, version),
  jdkStatusMissing: () => l10n.t('JDK missing'),
  jdkGetJdkErrorMessage: () => l10n.t('JDK 8 or higher not detected. This extension needs JDK to run the Java debug adapter. Please install JDK and set JAVA_HOME or put java on PATH.'),

  logJavaConfigured: (p: string) => l10n.t('Using configured java path: {0}', p),
  logJavaFromJavaHome: (p: string) => l10n.t('Using java from JAVA_HOME: {0}', p),
  logJavaFoundInPath: (p: string) => l10n.t('Found java on PATH: {0}', p),
  errJavaConfiguredMissing: (p: string) => l10n.t(
    "Configured java path does not exist: {0}. Set 'android-debugger.javaPath' to a valid java executable, or leave it as 'auto' to auto-detect.",
    p
  ),

  noWorkspace: () => l10n.t('No workspace is open.'),
  noWorkspaceFolderHint: () => l10n.t('No workspace folder is open. Please open a folder first.'),
  notInWorkspace: (path: string) => l10n.t('Selected path is not in any workspace folder: {0}', path),

  dirAdded: (rel: string) => l10n.t('Source dir added: {0}', rel),
  dirRemoved: (rel: string) => l10n.t('Source dir removed: {0}', rel),
  markUndo: () => l10n.t('Undo'),
  alreadyDir: (rel: string) => l10n.t('Already a source dir: {0}', rel),
  notADir: (rel: string) => l10n.t('Not a source dir: {0}', rel),
  dirCoveredByAncestor: (target: string, ancestor: string) =>
    l10n.t('"{0}" is already covered by marked source dir "{1}"', target, ancestor),

  sourceDirsEmpty: () => l10n.t('(No source dirs marked)'),
  sourceDirsHint: () => l10n.t('Right-click a folder → Mark as Debug Source Folder'),
  sourceDirsDecorationTooltip: () => l10n.t('Android Debugger source dir'),

  sourceDirsCountWord: () => l10n.t('source dir(s)'),
  sourceDirsCountZh: (n: number) => l10n.t('{0} source dir(s)', n),
  sourceDirsCountHint: () => l10n.t('Click to open the Android Debugger sidebar'),
  sourceDirsEmptyHint: () => l10n.t('Click to open the Android Debugger sidebar → right-click a folder to mark'),
  sourceDirsListTooltip: (paths: string[]) => l10n.t('Marked {0} source dir(s):\n{1}', paths.length, paths.join('\n')),
  // Shown once per session when clicking the status-bar source-dirs
  // count fails to open the sidebar (the VSCode builtin
  // `workbench.view.<id>` command is missing on some activation
  // orders). Tells the user where to find the sidebar and offers to
  // jump them straight to the Mark-as-Debug-Source-Folder command.
  openExplorerFallbackHint: () =>
    l10n.t(
      'Could not open the Android Debugger sidebar automatically. Click the "Android Debugger" icon in the Activity Bar (left side) to open it.'
    ),
  openExplorerFallbackAction: () => l10n.t('Mark a source folder now'),

  statusBarIdle: () => l10n.t('Android Debugger'),
  statusBarIdleTooltip: () => l10n.t('Click to run "Attach Debugger to Android Process"'),
  outputChannel: () => l10n.t('Android Debugger'),
  statusBarIndexing: (msg: string) => l10n.t('Indexing sources... {0}', msg),
  statusBarIndexingTooltip: () => l10n.t('Building SourceIndex'),
  statusBarAttaching: (msg: string) => l10n.t('Attaching... {0}', msg),
  statusBarAttachingTooltip: () => l10n.t('Attaching to target process'),
  statusBarAttached: (msg: string) => l10n.t('Attached {0}', msg),
  statusBarAttachedTooltip: () => l10n.t('Debug session is active'),
  statusBarError: (msg: string) => l10n.t('Error: {0}', msg),
  statusBarErrorTooltip: () => l10n.t('Click for details'),
  statusBarIdleDefault: () => l10n.t('Indexing sources...'),
  statusBarAttachingDefault: () => l10n.t('Attaching...'),
  statusBarAttachedDefault: () => l10n.t('Attached'),
  statusBarErrorDefault: () => l10n.t('Error'),
  statusBarIndexerFast: () => l10n.t('$(zap) Source indexer: local FS'),
  statusBarIndexerFastTooltip: () => l10n.t('Indexing local workspace files natively'),
  statusBarIndexerFallback: () => l10n.t('$(zap) Source indexer: remote proxy'),
  statusBarIndexerFallbackTooltip: () => l10n.t('Remote window — indexing goes through the remote FS proxy (works, slower)'),

  pickTargetDirTitle: () => l10n.t('Select target folder'),
  pickTargetDirOptionFile: () => l10n.t('Directory of current file'),
  pickTargetDirOptionWorkspace: () => l10n.t('Workspace root'),
  pickWorkspaceFolderTitle: () => l10n.t('Select a workspace folder'),

  processPickerTitle: () => l10n.t('Choose Android process to attach'),
  processPickerPlaceholder: () => l10n.t('Search by process name...'),

  pickDeviceTitle: () => l10n.t('Select Android device'),
  pickDevicePlaceholder: () => l10n.t('Pick a device to attach'),

  noDevices: () => l10n.t('No Android device detected. Please verify adb is connected (adb devices).'),
  devicesNotReady: (serialStates: string) => l10n.t('Devices not ready: {0}', serialStates),
  noJdwpProcesses: () => l10n.t('No debuggable JDWP process. Confirm the device is userdebug/eng and the process manifest is debuggable.'),

  attachPreparing: () => l10n.t('Preparing...'),
  attachCheckingDevices: () => l10n.t('Checking devices...'),
  attachEnumerating: () => l10n.t('Enumerating processes...'),
  attachForwarding: (pid: number) => l10n.t('Forwarding PID {0}...', pid),
  attachLaunchingAdapter: () => l10n.t('Launching Java adapter...'),
  attachDapInitializing: () => l10n.t('DAP initializing...'),
  attachAttach: () => l10n.t('attach...'),
  attachLargeIndexTitle: (n: number) => l10n.t('Source dir contains ~{0} files', n),
  attachLargeIndexMessage: (n: number, secs: number) => l10n.t('Indexing ~{0} files will take ~{1}s. Continue?', n, secs),
  attachLargeIndexContinue: () => l10n.t('Continue'),
  attachLargeIndexCancel: () => l10n.t('Cancel'),
  attachIndexCancelled: () => l10n.t('Indexing cancelled — debug session cannot start'),
  attachProgressTitle: (count: number) => l10n.t('Indexing {0} file(s)...', count),
  attachSuccess: (pid: number) => l10n.t('Attached to PID {0}, debug session is active', pid),
  attachSuccessLog: (pid: number, port: number) => l10n.t('Attach succeeded: PID {0} @ port {1}', pid, port),
  attachFail: (msg: string) => l10n.t('Attach failed: {0}', msg),
  adapterCapabilities: (caps: string) => l10n.t('Adapter capabilities: {0}', caps),
  adapterOutput: (output: string) => l10n.t('[Adapter Output] {0}', output),
  adapterAttachFailed: () => l10n.t('attach failed'),

  logAdbConfigured: (path: string) => l10n.t('Using configured adb path: {0}', path),
  logAdbConfiguredMissing: (path: string) => l10n.t('Configured adb path does not exist: {0}, attempting auto-detection', path),
  logAdbAutoFound: (path: string) => l10n.t('Found adb at common path: {0}', path),
  logAdbFoundInPath: (path: string) => l10n.t('Found adb on PATH: {0}', path),
  logAdbRunningFound: (path: string) => l10n.t('Reusing running adb at {0}', path),
  errAdbConfiguredMissing: (path: string) => l10n.t(
    "Configured adb path does not exist: {0}. Set 'android-debugger.adbPath' to a valid adb binary, or leave it as 'auto' to auto-detect.",
    path
  ),
  errAdbNotFound: () => l10n.t(
    "adb not found in common install paths or $PATH. Install the Android SDK platform-tools, or set 'android-debugger.adbPath' to your adb binary."
  ),
  logSpawningAdb: (path: string, args: string) => l10n.t('Spawning adb: {0} {1}', path, args),
  logRunningAdb: (path: string, args: string) => l10n.t('Running adb: {0} {1}', path, args),
  logJdwpFailed: (stderr: string, stdout: string) => l10n.t('adb jdwp -l failed: {0}', stderr.trim() || stdout.trim()),
  logForwardFailed: (port: number) => l10n.t('adb forward tcp:{0} jdwp:{pid} failed', port),
  logForwardEstablished: (port: number, pid: number) => l10n.t('adb forward established: tcp:{0} -> jdwp:{1}', port, pid),
  logForwardRemoveFailed: (port: number, stderr: string) => l10n.t('Failed to remove adb forward tcp:{0}: {1}', port, stderr.trim()),
  logForwardRemoved: (port: number) => l10n.t('adb forward removed: tcp:{0}', port),
  logDapParseFailed: (line: string) => l10n.t('Failed to parse DAP message: {0}', line),
  logAdapterSpawn: (javaPath: string, args: string) => l10n.t('Spawning Java adapter: {0} {1}', javaPath, args),
  logAdapterExited: (code: number | null, signal: string | null) => l10n.t('Java adapter exited: code={0} signal={1}', String(code), String(signal)),
  logAdapterSpawnFail: () => l10n.t('Java adapter failed to spawn'),
  logAdapterJarFound: (path: string) => l10n.t('Found adapter jar: {0}', path),
  errAdapterJarMissing: (candidates: string) => l10n.t("Java adapter jar not found. Please run 'npm run build:adapter' from the repo root to build the artifact. Probed:\n{0}", candidates),
  errAdapterNotReadyTimeout: (ms: number) => l10n.t('Java adapter did not become ready within {0} ms', ms),
  errAdapterExitBeforeReady: (code: number | null, signal: string | null) =>
    l10n.t('Java adapter exited before ready (code={0} signal={1})', String(code), String(signal)),
  errAllocateJdwpPortFailed: (attempts: number) =>
    l10n.t('Failed to allocate JDWP port after {0} attempts', attempts),
  errAllocateEphemeralPortFailed: () => l10n.t('Failed to obtain ephemeral port'),
  errBindPortFailed: () => l10n.t('Failed to bind port'),
  indexProgress: (done: number, total: number, dirs: number) =>
    l10n.t('{0}/{1} files in {2} dir(s)', done, total, dirs),
  pidDescription: (pid: number) => l10n.t('PID {0}', pid),
  restartNoContext: () => l10n.t(
    'Cannot restart debug session: no attach context available. Run "Attach Debugger to Android Process" first.'
  ),
  restartNoActiveSession: () => l10n.t(
    'No active Android Debugger session. Run "Attach Debugger to Android Process" first.'
  ),
  restartNoPid: () => l10n.t(
    'Active Android Debugger session has no target PID recorded (started by an older extension version). Detach and re-attach to use Restart.'
  ),
};
