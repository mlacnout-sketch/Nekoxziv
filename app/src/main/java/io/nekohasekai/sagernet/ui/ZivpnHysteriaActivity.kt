package io.nekohasekai.sagernet.ui

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import io.nekohasekai.sagernet.R

class ZivpnHysteriaActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zivpn_hysteria)
        
        supportActionBar?.title = "ZIVPN Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = getSharedPreferences("zivpn_prefs", Context.MODE_PRIVATE)

        val etServerIp = findViewById<EditText>(R.id.et_server_ip)
        val etPassword = findViewById<EditText>(R.id.et_password)
        val etObfs = findViewById<EditText>(R.id.et_obfs)
        val etPortRange = findViewById<EditText>(R.id.et_port_range)
        val etCoreCount = findViewById<EditText>(R.id.et_core_count)
        val etSpeedLimit = findViewById<EditText>(R.id.et_speed_limit)
        val btnSave = findViewById<Button>(R.id.btn_save)

        // Load saved values
        etServerIp.setText(prefs.getString("server_ip", "103.175.216.5"))
        etPassword.setText(prefs.getString("password", "maslexx68"))
        etObfs.setText(prefs.getString("obfs", "hu``hqb`c"))
        etPortRange.setText(prefs.getString("port_range", "6000-19999"))
        etCoreCount.setText(prefs.getInt("core_count", 8).toString())
        etSpeedLimit.setText(prefs.getInt("speed_limit", 50).toString())

        btnSave.setOnClickListener {
            prefs.edit().apply {
                putString("server_ip", etServerIp.text.toString())
                putString("password", etPassword.text.toString())
                putString("obfs", etObfs.text.toString())
                putString("port_range", etPortRange.text.toString())
                putInt("core_count", etCoreCount.text.toString().toIntOrNull() ?: 8)
                putInt("speed_limit", etSpeedLimit.text.toString().toIntOrNull() ?: 50)
                apply()
            }
            Toast.makeText(this, "Settings Saved. Restart VPN to apply.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
