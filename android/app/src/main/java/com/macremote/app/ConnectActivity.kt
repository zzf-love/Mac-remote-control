package com.macremote.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

/** 连接页：填 Mac 的地址（推荐 Tailscale IP）、端口和 VNC 密码。 */
class ConnectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)

        val prefs = getSharedPreferences("connection", Context.MODE_PRIVATE)
        val hostInput = findViewById<EditText>(R.id.input_host)
        val portInput = findViewById<EditText>(R.id.input_port)
        val passwordInput = findViewById<EditText>(R.id.input_password)

        hostInput.setText(prefs.getString("host", ""))
        portInput.setText(prefs.getString("port", "5900"))
        passwordInput.setText(prefs.getString("password", ""))

        findViewById<Button>(R.id.btn_connect).setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull()
            val password = passwordInput.text.toString()

            if (host.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_no_host), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (port == null || port !in 1..65535) {
                Toast.makeText(this, getString(R.string.error_bad_port), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("host", host)
                .putString("port", port.toString())
                .putString("password", password)
                .apply()

            startActivity(
                Intent(this, RemoteActivity::class.java)
                    .putExtra(RemoteActivity.EXTRA_HOST, host)
                    .putExtra(RemoteActivity.EXTRA_PORT, port)
                    .putExtra(RemoteActivity.EXTRA_PASSWORD, password)
            )
        }
    }
}
