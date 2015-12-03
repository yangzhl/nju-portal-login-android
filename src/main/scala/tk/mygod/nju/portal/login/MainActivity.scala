package tk.mygod.nju.portal.login

import android.content._
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.net.Uri
import android.os.{IBinder, Bundle}
import android.provider.Settings
import android.support.v7.app.AlertDialog
import tk.mygod.app.ToolbarActivity

object MainActivity {
  private val askedBoundConnection = "misc.askedBoundConnection"
}

final class MainActivity extends ToolbarActivity with OnSharedPreferenceChangeListener {
  import MainActivity._

  private lazy val serviceIntent = intentService[NetworkMonitor]
  private val connection = new ServiceConnection {
    def onServiceConnected(name: ComponentName, binder: IBinder) =
      service = binder.asInstanceOf[NetworkMonitor#ServiceBinder].service
    def onServiceDisconnected(name: ComponentName) = service = null
  }
  var service: NetworkMonitor = _

  override protected def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    configureToolbar()
    testBoundConnections()
    startNetworkMonitor
    app.pref.registerOnSharedPreferenceChangeListener(this)
  }

  private def manageWriteSettings(dialog: DialogInterface = null, which: Int = 0) = startActivity(
    new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:" + getPackageName)))
  def testBoundConnections(requested: Boolean = false) = app.boundConnectionsAvailable match {
    case 1 => if (requested) {
      manageWriteSettings()
      true
    } else if (!app.pref.getBoolean(askedBoundConnection, false)) {
      new AlertDialog.Builder(this).setTitle(R.string.bound_connections_title)
        .setPositiveButton(android.R.string.yes, manageWriteSettings: DialogInterface.OnClickListener)
        .setMessage(R.string.bound_connections_message).setNegativeButton(android.R.string.no, null).create.show
      app.editor.putBoolean(askedBoundConnection, true)
      true
    } else false
    case 2 => if (requested) {
      manageWriteSettings()
      true
    } else false
    case _ => false
  }

  override def onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) = key match {
    case AUTO_CONNECT_ENABLED =>
      val value = app.autoConnectEnabled
      app.editor.putBoolean(AUTO_CONNECT_ENABLED, value)
      if (value) {
        getPackageManager.setComponentEnabledSetting(new ComponentName(this, classOf[NetworkMonitorListener]),
          PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        startNetworkMonitor
      } else {
        getPackageManager.setComponentEnabledSetting(new ComponentName(this, classOf[NetworkMonitorListener]),
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        stopService(serviceIntent)
      }
    case RELOGIN_DELAY => if (NetworkMonitor.instance != null) NetworkMonitor.instance.reloginThread.interrupt
    case _ => // ignore
  }

  def startNetworkMonitor = if (app.autoConnectEnabled) {
    startService(serviceIntent)
    bindService(serviceIntent, connection, 0)
  }

  override protected def onDestroy = {
    super.onDestroy
    unbindService(connection)
  }
}
