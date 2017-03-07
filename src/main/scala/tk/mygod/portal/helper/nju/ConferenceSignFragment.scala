package tk.mygod.portal.helper.nju

import java.net.{HttpURLConnection, URL, URLEncoder}
import java.security.spec.PKCS8EncodedKeySpec
import java.security.{KeyFactory, Signature}
import java.text.SimpleDateFormat
import java.util.Date

import android.app.DialogFragment
import android.os.Bundle
import android.support.design.widget.TextInputEditText
import android.support.v7.widget.AppCompatTextView
import android.util.{Base64, Log}
import android.view.inputmethod.EditorInfo
import android.view.{KeyEvent, View}
import be.mygod.app.FragmentPlus
import be.mygod.util.CloseUtils._
import be.mygod.util.IOUtils
import org.json.{JSONException, JSONObject}
import tk.mygod.portal.helper.nju.PortalManager.{InvalidResponseException, UnexpectedResponseCodeException}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.collection.JavaConversions._

object ConferenceSignFragment {
  private final val TAG = "ConferenceSignFragment"
  private final val DATE_FORMAT = new SimpleDateFormat("yyyyMMddHHmmss")
  private final val DEVICEID = "6756"
  private final val KEY = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(
    "MIICdgIBADANBgkqhkiG9w0BAQEFAASCAmAwggJcAgEAAoGBAIaEhtCJEL3oq50S5d1sLX5Lb4uLEUSI7u3rzgDIv/3cEpSoANrXwM3qgC4U66ygmohhDjMbjTFiZfLRm4nWHKNk3WANInimM99s5FHKKooC0d0I+rwUbk2WZr0uOdDnx9lGJxqdRolMtYBI/pZCpBDRj2ogEv5Oxr9f9tARg3LvAgMBAAECgYBTN2MrWM/ZnDmmZ016qHSQX9x2qCabjla5Kxp607YqNt3rxu8Yc0acXIjFeT2+wnA3FEuzhETZmzTUfaVKJQH7kRaOlRvksYwg/wSNB/irD8N2Lmw6XX7AvHRvzC5qMbNjUiO2vQwFUjorxES93Bpe5smzc0vOlL3UjPxQXvwZkQJBAMHC5OPcF05xZlob4I4yiFBzThq8WLHKp2Lzq9fYeBW2t90St659HHYqQc/E5FlOfZnCqh6k9/cEa8e2qpCpD9cCQQCxuf6e0y8HHv4iIsfMvpwNkc/MSgoXKFYL0EpLb68vxgWf0WSGhUQ13Hoyk/TzgVBn0O229rxxexsbujl/sDKpAkAixYfwAEJKeH1GtHQC8LyXu2mL0LsWBOkvD82J6bX7J5QtXzuJW7hs2D6BO7NC95wAqPeAklhRgwCYkYZgeYZ3AkEAjD2aH7XBDDt2iXUsd/GIrmR6tldOMwvPKi9IENKmSGpXkc7nJgcO1fmOK075IRTPX7xLd+6msF1V/MEsEgf1UQJAeYjz5SREy1ztwaKp2aKWr9kIRjqcvUm2E0CD8pccn+MI7PKjg1VFFEKHPGXixRGTSbbolxyP3CegdzJfr3hAVQ==",
    Base64.NO_WRAP)))

  def openSignConnection(file: String, data: String): JSONObject = {
    val url = new URL(HTTP, "114.212.5.2", 8088, "/yktpre/services/conference/" + file)
    val conn = (if (NetworkMonitor.instance != null && app.boundConnectionsAvailable > 1) {
      val network = NetworkMonitor.instance.listener.preferredNetwork
      if (network == null) url.openConnection() else network.openConnection(url)
    } else {
      NetworkMonitor.preferNetworkLegacy()
      url.openConnection()
    }).asInstanceOf[HttpURLConnection]
    conn.setInstanceFollowRedirects(false)
    conn.setConnectTimeout(5000)
    conn.setReadTimeout(2000)
    conn.setUseCaches(false)
    conn.addRequestProperty("Content-Type", "text/plain")
    conn.addRequestProperty("accept", "*/*")
    conn.addRequestProperty("Connection", "Keep-Alive")
    conn.addRequestProperty("Charset", "UTF-8")
    conn.setRequestMethod("POST")
    conn.setDoOutput(true)
    //noinspection JavaAccessorMethodCalledAsEmptyParen
    autoClose(conn.getOutputStream())(os => IOUtils.writeAllText(os, data))
    if (conn.getResponseCode >= 400) throw new UnexpectedResponseCodeException(conn)
    //noinspection JavaAccessorMethodCalledAsEmptyParen
    val result = autoClose(conn.getInputStream())(IOUtils.readAllText)
    if (BuildConfig.DEBUG) Log.d(TAG, result)
    try new JSONObject(result) catch {
      case _: JSONException => throw InvalidResponseException(url, result)
    }
  }
}

class ConferenceSignFragment extends DialogFragment with FragmentPlus {
  import ConferenceSignFragment._

  override def layout: Int = R.layout.fragment_conference_sign

  private var conId: Int = _

  override def onViewCreated(view: View, savedInstanceState: Bundle) {
    super.onViewCreated(view, savedInstanceState)
    val button = view.findViewById(R.id.query_button)
    Future {
      try {
        val now = DATE_FORMAT.format(new Date())
        val sign = Signature.getInstance("SHA1withRSA")
        sign.initSign(KEY)
        sign.update((DEVICEID + now).getBytes("GBK"))
        val json = openSignConnection("list", "deviceid=%s&timestamp=%s&sign=%s".format(DEVICEID, now,
          URLEncoder.encode(Base64.encodeToString(sign.sign(), Base64.NO_WRAP), "UTF-8")))
        json.getInt("retcode") match {
          case 0 => json.getJSONArray("data").optJSONObject(0) match {
            case null =>
              app.showToast("无会议信息")
              dismissAllowingStateLoss()
            case conf =>
              var conName: String = null
              var typeName: String = null
              var conBeginDate: String = null
              var conBeginTime: String = null
              var conEndTime: String = null
              var roomName: String = null
              var conSignTime: String = null
              for (key <- conf.keys) key match {
                case "device_id" => // must be DEVICEID, ignore
                case "con_id" => conId = conf.getInt(key)
                case "con_begindate" => conBeginDate = conf.getString(key)
                case "con_signtime" => conSignTime = conf.getString(key)
                case "con_begintime" => conBeginTime = conf.getString(key)
                case "con_endtime" => conEndTime = conf.getString(key)
                case "con_name" => conName = conf.getString(key)
                case "open" => // must be 2
                case "type_name" => typeName = conf.getString(key)
                case "room_name" => roomName = conf.getString(key)
                case _ => Log.e(TAG, "Unknown key in list data: " + key)
              }
              val result = "会议名称：%s\n会议类型：%s\n会议日期：%s %s-%s\n会议地点：%s\n会议签到时间：%s\n".format(
                conName, typeName, conBeginDate, conBeginTime, conEndTime, roomName, conSignTime)
              app.handler.post(() => {
                button.setEnabled(true)
                view.findViewById(R.id.details).asInstanceOf[AppCompatTextView].setText(result)
              })
          }
          case code =>
            app.showToast("#%d: %s".format(code, json.getString("retmsg")))
            dismissAllowingStateLoss()
        }
      } catch {
        case e: Exception =>
          app.showToast(e.getMessage)
          e.printStackTrace()
          dismissAllowingStateLoss()
      }
    }
    val idInput = view.findViewById(R.id.id_input).asInstanceOf[TextInputEditText]
    idInput.setText(PortalManager.username)
    idInput.setOnEditorActionListener((_, actionId, event) => if (button.isEnabled &&
      (actionId == EditorInfo.IME_ACTION_SEND ||
        event.getKeyCode == KeyEvent.KEYCODE_ENTER && event.getAction == KeyEvent.ACTION_DOWN)) {
      ThrowableFuture {
        // TODO: how to sign with card (signtype=1)?
        val json = openSignConnection("sign", "conid=%s&signtype=2&signdata=%s".format(conId, idInput.getText))
        val code = json.getInt("retcode")
        val result = new StringBuilder("#")
        result.append(code)
        result.append(": ")
        result.append(json.getString("retmsg"))
        if (code == 0) {
          result.append('\n')
          result.append(json.getString("stuempno"))
          result.append(' ')
          result.append(json.getString("custname"))
          result.append(' ')
          result.append(json.getString("deptname"))
        }
        app.showToast(result.toString)
      }
      true
    } else false)
    button.setOnClickListener(_ => if (button.isEnabled) {
      button.setEnabled(false)
      ThrowableFuture {
        try {
          val json = openSignConnection("atts", "conid=" + conId)
          app.showToast(json.getInt("retcode") match {
            case 0 => "%d/%d".format(json.getInt("qd"), json.getInt("yd"))
            case code => "#%d: %s".format(code, json.getString("retmsg"))
          })
        } finally app.handler.post(() => button.setEnabled(true))
      }
    })
  }
}
