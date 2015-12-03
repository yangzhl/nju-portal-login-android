package tk.mygod.nju.portal.login

import java.net._

import android.annotation.TargetApi
import android.net.{NetworkInfo, Network}
import android.util.Log
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization
import tk.mygod.os.Build
import tk.mygod.util.CloseUtils._
import tk.mygod.util.IOUtils

/**
  * Portal manager. Supports v=201510210840.
  *
  * @author Mygod
  */
//noinspection JavaAccessorMethodCalledAsEmptyParen
object PortalManager {
  private val TAG = "PortalManager"

  private val portalDomain = "p.nju.edu.cn"

  private val status = "status"

  private var userInfoListener: JObject => Any = _
  def setUserInfoListener(listener: JObject => Any) {
    userInfoListener = listener
    if (listener == null) return
    val info = App.instance.pref.getString(status, "")
    if (!info.isEmpty) listener(parse(info).asInstanceOf[JObject])
  }

  private implicit val formats = Serialization.formats(NoTypeHints)
  private def processResult(resultStr: String) = {
    if (App.DEBUG) Log.v(TAG, resultStr)
    val json = parse(resultStr)
    val code = (json \ "reply_code").asInstanceOf[JInt].values.toInt
    val info = json \ "userinfo"
    info match {
      case obj: JObject =>
        App.instance.editor.putString(status, compact(render(info))).apply
        if (userInfoListener != null) App.handler.post(() => userInfoListener(obj))
      case _ =>
    }
    if (App.instance.pref.getBoolean("notifications.login", true))
      App.instance.showToast("#%d: %s".format(code, (json \ "reply_msg").asInstanceOf[JString].values))
    code
  }

  //noinspection ScalaDeprecation
  @TargetApi(21)
  def reportNetworkConnectivity(network: Network, hasConnectivity: Boolean) = if (Build.version >= 23)
    App.instance.cm.reportNetworkConnectivity(network, hasConnectivity) else App.instance.cm.reportBadNetwork(network)

  /**
    * Setup HttpURLConnection.
    *
    * @param conn HttpURLConnection.
    * @param timeout Connect/read timeout.
    * @param output 0-2: Nothing, post, post username/password.
    */
  def setup(conn: HttpURLConnection, timeout: Int, output: Int = 0) {
    conn.setInstanceFollowRedirects(false)
    conn.setConnectTimeout(timeout)
    conn.setReadTimeout(timeout)
    conn.setUseCaches(false)
    if (output == 0) return
    conn.setRequestMethod("POST")
    if (output == 1) return
    conn.setDoOutput(true)
    //noinspection JavaAccessorMethodCalledAsEmptyParen
    autoClose(conn.getOutputStream())(os => IOUtils.writeAllText(os, "username=%s&password=%s".format(
      App.instance.pref.getString("account.username", ""), App.instance.pref.getString("account.password", ""))))
  }

  private case class CaptivePortalException() extends Exception
  /**
    * Based on: https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/connectivity/NetworkMonitor.java#640
    */
  private def loginCore(conn: URL => URLConnection) = {
    if (App.DEBUG) Log.d(TAG, "Logging in...")
    var code: Option[Int] = None
    try autoDisconnect(conn(new URL(App.http, portalDomain, "/portal_io/login")).asInstanceOf[HttpURLConnection])
    { conn =>
      setup(conn, App.instance.loginTimeout, 2)
      code = Some(conn.getResponseCode)
      if (!code.contains(200)) throw new CaptivePortalException
      val result = processResult(IOUtils.readAllText(conn.getInputStream()))
      (if (result == 3 || result == 8) 2 else 0, result)
    } catch {
      case e: CaptivePortalException =>
        if (App.DEBUG) Log.w(TAG, "Unknown response code: " + code)
        (2, 0)
      case e: ParserUtil.ParseException =>
        if (App.DEBUG) Log.w(TAG, "Parse failed: " + e.getMessage)
        (2, 0)
      case e: SocketException =>
        App.instance.showToast(e.getMessage)
        e.printStackTrace
        (2, 0)
      case e: SocketTimeoutException =>
        App.instance.showToast(App.instance.getString(R.string.error_socket_timeout))
        (1, 0)
      case e: UnknownHostException =>
        App.instance.showToast(e.getMessage)
        (1, 0)
      case e: Exception =>
        App.instance.showToast(e.getMessage)
        e.printStackTrace
        (1, 0)
    }
  }
  @TargetApi(21)
  def login(network: Network) = {
    val (code, result) = loginCore(network.openConnection)
    if (result == 1 || result == 6) reportNetworkConnectivity(network, true)
    (code, result)
  }
  def loginLegacy(network: NetworkInfo = null) = loginCore({
    NetworkMonitor.preferNetworkLegacy(network)
    _.openConnection
  })
  def login: (Int, Int) = {
    if (NetworkMonitor.instance != null && App.instance.boundConnectionsAvailable > 1) {
      val network = NetworkMonitor.instance.listener.preferredNetwork
      if (network != null) return login(network)
    }
    loginLegacy()
  }

  def logout = try {
    val url = new URL(App.http, portalDomain, "/portal_io/logout")
    var network: Network = null
    autoDisconnect((if (NetworkMonitor.instance != null && App.instance.boundConnectionsAvailable > 1) {
      network = NetworkMonitor.instance.listener.preferredNetwork
      if (network != null) network.openConnection(url) else {
        NetworkMonitor.preferNetworkLegacy()
        url.openConnection
      }
    } else {
      NetworkMonitor.preferNetworkLegacy()
      url.openConnection
    }).asInstanceOf[HttpURLConnection]) { conn =>
      setup(conn, App.instance.loginTimeout, 1)
      if (processResult(IOUtils.readAllText(conn.getInputStream())) == 101 &&
        App.instance.boundConnectionsAvailable > 1 && network != null)
        reportNetworkConnectivity(network, false)
    }
    if (NetworkMonitor.instance != null && NetworkMonitor.instance.listener != null)
      NetworkMonitor.instance.listener.loginedNetwork = null
    NetworkMonitor.listenerLegacy.loginedNetwork = null
  } catch {
    case e: Exception =>
      App.instance.showToast(e.getMessage)
      e.printStackTrace
  }
}
