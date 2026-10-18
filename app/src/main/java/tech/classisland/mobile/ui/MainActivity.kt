package tech.classisland.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import tech.classisland.mobile.R
import tech.classisland.mobile.databinding.ActivityMainBinding
import tech.classisland.mobile.engine.ScheduleEngine
import tech.classisland.mobile.engine.Store
import java.time.LocalDate

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = PagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 3
        binding.viewPager.isUserInputEnabled = false

        binding.bottomNav.setOnItemSelectedListener {
            binding.viewPager.currentItem = when (it.itemId) {
                R.id.nav_today -> 0
                R.id.nav_timetable -> 1
                else -> 2
            }
            true
        }

        requestNotificationPermission()
        refreshWeekInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshWeekInfo()
    }

    private fun refreshWeekInfo() {
        val wi = ScheduleEngine.weekIndex(LocalDate.now(), Store.firstWeekDate)
        binding.tvWeekInfo.text = "第 $wi 周"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    private class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> TodayFragment()
            1 -> TimetableFragment()
            else -> SettingsFragment()
        }
    }
}
