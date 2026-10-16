package com.ilactakipmobil.app

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ilactakipmobil.app.adapter.GecmisAdapter
import com.ilactakipmobil.app.adapter.IlacAdapter
import com.ilactakipmobil.app.adapter.TakvimAdapter
import com.ilactakipmobil.app.adapter.TakvimGridAdapter
import com.ilactakipmobil.app.alarm.AlarmScheduler
import com.ilactakipmobil.app.alarm.AlarmSoundManager
import com.ilactakipmobil.app.data.IlacRepository
import com.ilactakipmobil.app.databinding.ActivityMainBinding
import com.ilactakipmobil.app.databinding.DialogAddIlacBinding
import com.ilactakipmobil.app.databinding.DialogAudioRecorderBinding
import com.ilactakipmobil.app.db.DozKaydiEntity
import com.ilactakipmobil.app.model.Ilac
import com.ilactakipmobil.app.model.TakvimGunModel
import com.ilactakipmobil.app.ocr.OcrHelper
import com.ilactakipmobil.app.util.LocaleHelper
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ilacAdapter: IlacAdapter
    private lateinit var takvimAdapter: TakvimAdapter
    private lateinit var takvimGridAdapter: TakvimGridAdapter
    private lateinit var gecmisAdapter: GecmisAdapter
    private lateinit var repository: IlacRepository

    private var secilenTakvimCalendar: Calendar = Calendar.getInstance()
    private var secilenGecmisIlacFiltre: String = "Tüm İlaçlar"
    private var secilenGecmisPeriyot: String = "BUGUN" // BUGUN, HAFTA, TUM

    private var tempCameraImageUri: Uri? = null
    private var ocrTargetDialogBinding: DialogAddIlacBinding? = null

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraImageUri != null) {
            processOcrImage(tempCameraImageUri!!)
        } else {
            ocrTargetDialogBinding?.pbOcrLoading?.visibility = View.GONE
        }
    }

    private val takePicturePreviewLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            processOcrBitmap(bitmap)
        } else {
            ocrTargetDialogBinding?.pbOcrLoading?.visibility = View.GONE
        }
    }

    private val pickGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            processOcrImage(uri)
        } else {
            ocrTargetDialogBinding?.pbOcrLoading?.visibility = View.GONE
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraForOcr()
        } else {
            Toast.makeText(this, "Kamera izni reddedildi. Galeriden fotoğraf seçebilirsiniz.", Toast.LENGTH_LONG).show()
            pickGalleryLauncher.launch("image/*")
        }
    }

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            gosterSesKayitDialogu()
        } else {
            Toast.makeText(this, getString(R.string.toast_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCameraForOcr() {
        tempCameraImageUri = createTempImageUri()
        if (tempCameraImageUri != null) {
            try {
                takePictureLauncher.launch(tempCameraImageUri!!)
            } catch (_: Exception) {
                takePicturePreviewLauncher.launch(null)
            }
        } else {
            takePicturePreviewLauncher.launch(null)
        }
    }

    private fun createTempImageUri(): Uri? {
        return try {
            val imagesDir = File(cacheDir, "images").apply { if (!exists()) mkdirs() }
            val tempFile = File.createTempFile("ocr_box_", ".jpg", imagesDir)
            FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                tempFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun processOcrBitmap(bitmap: Bitmap) {
        val dialogBinding = ocrTargetDialogBinding ?: return
        dialogBinding.pbOcrLoading.visibility = View.VISIBLE
        dialogBinding.tvOzetOcr.visibility = View.GONE

        OcrHelper.processBitmap(
            bitmap = bitmap,
            onSuccess = { result ->
                dialogBinding.pbOcrLoading.visibility = View.GONE
                if (result.ilacAdi.isNotEmpty()) {
                    dialogBinding.etIlacAdi.setText(result.ilacAdi)
                }
                if (result.urunGucuDozaj.isNotEmpty()) {
                    dialogBinding.etDozaj.setText(result.urunGucuDozaj)
                }

                if (result.ilacAdi.isNotEmpty() || result.urunGucuDozaj.isNotEmpty()) {
                    dialogBinding.tvOzetOcr.text = "✨ ML Kit İlaç Tespiti: ${result.ilacAdi} (${result.urunGucuDozaj})"
                    dialogBinding.tvOzetOcr.visibility = View.VISIBLE
                    Toast.makeText(this, "İlaç kutusu başarıyla tarandı!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Fotoğrafta net metin okunamadı. Lütfen elle girin.", Toast.LENGTH_LONG).show()
                }
            },
            onError = { e ->
                dialogBinding.pbOcrLoading.visibility = View.GONE
                Toast.makeText(this, "Tarama hatası: ${e.message}. Lütfen elle girin.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun processOcrImage(uri: Uri) {
        val dialogBinding = ocrTargetDialogBinding ?: return
        dialogBinding.pbOcrLoading.visibility = View.VISIBLE
        dialogBinding.tvOzetOcr.visibility = View.GONE

        OcrHelper.processImage(
            context = this,
            imageUri = uri,
            onSuccess = { result ->
                dialogBinding.pbOcrLoading.visibility = View.GONE
                if (result.ilacAdi.isNotEmpty()) {
                    dialogBinding.etIlacAdi.setText(result.ilacAdi)
                }
                if (result.urunGucuDozaj.isNotEmpty()) {
                    dialogBinding.etDozaj.setText(result.urunGucuDozaj)
                }

                if (result.ilacAdi.isNotEmpty() || result.urunGucuDozaj.isNotEmpty()) {
                    dialogBinding.tvOzetOcr.text = "✨ ML Kit İlaç Tespiti: ${result.ilacAdi} (${result.urunGucuDozaj})"
                    dialogBinding.tvOzetOcr.visibility = View.VISIBLE
                    Toast.makeText(this, "İlaç kutusu başarıyla tarandı!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Fotoğrafta net metin okunamadı. Lütfen elle girin.", Toast.LENGTH_LONG).show()
                }
            },
            onError = { e ->
                dialogBinding.pbOcrLoading.visibility = View.GONE
                Toast.makeText(this, "Tarama hatası: ${e.message}. Lütfen elle girin.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Bildirim izni verildi.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bildirim izni reddedildi. Alarmlar sessiz kalabilir.", Toast.LENGTH_LONG).show()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IlacRepository(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupToolbarMenu()
        izinleriKontrolEt()
        setupRecyclerViews()
        setupBottomNavigation()
        setupTakvimControls()
        setupGecmisControls()

        binding.fabEkle.setOnClickListener {
            yeniIlacEkleDialogGoster()
        }

        // Başlangıç verilerini yükle ve varsayılan olarak İlaçlarım sekmesini aç
        ilaclariYereldenYukle()
        ilaclariSunucudanSenkronizeEt()
        binding.bottomNavigationView.selectedItemId = R.id.navigation_ilaclar
    }

    private fun setupToolbarMenu() {
        binding.toolbar.inflateMenu(R.menu.main_toolbar_menu)
        guncelleToolbarDilMenuMetni()

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_record_sound -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        gosterSesKayitDialogu()
                    } else {
                        requestRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    true
                }
                R.id.action_toggle_lang -> {
                    val currentLang = LocaleHelper.getLanguage(this)
                    val newLang = if (currentLang == "tr") "en" else "tr"
                    LocaleHelper.setLanguage(this, newLang)
                    recreate()
                    true
                }
                else -> false
            }
        }
    }

    private fun gosterSesKayitDialogu() {
        val dialogBinding = DialogAudioRecorderBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        val customFile = File(filesDir, "custom_alarm_sound.3gp")
        val prefs = getSharedPreferences("ilac_takip_prefs", Context.MODE_PRIVATE)
        val useCustom = prefs.getBoolean("key_use_custom_sound", false)

        dialogBinding.switchOzelSesKullan.isChecked = useCustom && customFile.exists()

        if (customFile.exists()) {
            dialogBinding.tvKayitDurumu.text = getString(R.string.status_recorded)
            dialogBinding.btnSesiDinle.isEnabled = true
        } else {
            dialogBinding.tvKayitDurumu.text = getString(R.string.status_no_recording)
            dialogBinding.btnSesiDinle.isEnabled = false
        }

        var mediaRecorder: MediaRecorder? = null
        var mediaPlayer: MediaPlayer? = null

        dialogBinding.btnKaydiBaslat.setOnClickListener {
            try {
                if (customFile.exists()) {
                    customFile.delete()
                }
                mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(customFile.absolutePath)
                    prepare()
                    start()
                }

                dialogBinding.tvKayitDurumu.text = getString(R.string.status_recording)
                dialogBinding.btnKaydiBaslat.isEnabled = false
                dialogBinding.btnKaydiDurdur.isEnabled = true
                dialogBinding.btnSesiDinle.isEnabled = false
                Toast.makeText(this, getString(R.string.toast_recording_started), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Kayıt başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnKaydiDurdur.setOnClickListener {
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                mediaRecorder = null

                dialogBinding.tvKayitDurumu.text = getString(R.string.status_recorded)
                dialogBinding.btnKaydiBaslat.isEnabled = true
                dialogBinding.btnKaydiDurdur.isEnabled = false
                dialogBinding.btnSesiDinle.isEnabled = customFile.exists()
                Toast.makeText(this, getString(R.string.toast_recording_stopped), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        dialogBinding.btnSesiDinle.setOnClickListener {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(customFile.absolutePath)
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Dinleme hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialogBinding.btnIptal.setOnClickListener {
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
            dialog.dismiss()
        }

        dialogBinding.btnKaydet.setOnClickListener {
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}

            val useActive = dialogBinding.switchOzelSesKullan.isChecked
            prefs.edit().putBoolean("key_use_custom_sound", useActive && customFile.exists()).apply()
            Toast.makeText(this, getString(R.string.toast_audio_saved), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {}
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
        }

        dialog.show()
    }

    private fun guncelleToolbarDilMenuMetni() {
        val currentLang = LocaleHelper.getLanguage(this)
        val menuItem = binding.toolbar.menu.findItem(R.id.action_toggle_lang)
        if (currentLang == "en") {
            menuItem?.title = "🇬🇧 EN (🇹🇷 TR)"
        } else {
            menuItem?.title = "🇹🇷 TR (🇬🇧 EN)"
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmSoundManager.stop()
        if (binding.layoutTakvimContainer.visibility == View.VISIBLE) {
            takvimVerileriniYukle()
        } else if (binding.layoutGecmisContainer.visibility == View.VISIBLE) {
            gecmisVerileriniYukle()
        }
    }

    private fun izinleriKontrolEt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intent)
            }
        }
    }

    private fun setupRecyclerViews() {
        // 1. İlaçlarım Listesi
        ilacAdapter = IlacAdapter(
            ilaclar = mutableListOf(),
            onToggleDurum = { ilac -> durumDegistir(ilac) },
            onSil = { ilac -> ilacSilOnayGoster(ilac) }
        )
        binding.rvIlaclar.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = ilacAdapter
        }

        // 2. Uygulama İçi Aylık Takvim Izgarası (7 Sütunlu Grid)
        takvimGridAdapter = TakvimGridAdapter(
            gunler = mutableListOf(),
            onGunSecildi = { gunModel ->
                val parts = gunModel.tarihStr.split("-")
                if (parts.size == 3) {
                    secilenTakvimCalendar.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                    takvimVerileriniYukle()
                }
            }
        )
        binding.rvTakvimGrid.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 7)
            adapter = takvimGridAdapter
        }

        // 3. Takvim Doz Listesi
        takvimAdapter = TakvimAdapter(
            dozlar = mutableListOf(),
            onAldim = { doz -> dozuOnayla(doz, DozKaydiEntity.DURUM_ALINDI) },
            onAtladim = { doz -> dozuOnayla(doz, DozKaydiEntity.DURUM_ATLANDI) },
            onErtele = { doz -> dozuErteleDialogGoster(doz) }
        )
        binding.rvTakvimDozlar.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = takvimAdapter
        }

        // 4. Kullanım Geçmişi Listesi
        gecmisAdapter = GecmisAdapter(dozlar = mutableListOf())
        binding.rvGecmisDozlar.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = gecmisAdapter
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_ilaclar -> {
                    binding.toolbar.title = getString(R.string.title_ilaclarim)
                    binding.layoutIlaclarContainer.visibility = View.VISIBLE
                    binding.layoutTakvimContainer.visibility = View.GONE
                    binding.layoutGecmisContainer.visibility = View.GONE
                    ilaclariYereldenYukle()
                    true
                }
                R.id.navigation_takvim -> {
                    binding.toolbar.title = getString(R.string.title_takvim)
                    binding.layoutIlaclarContainer.visibility = View.GONE
                    binding.layoutTakvimContainer.visibility = View.VISIBLE
                    binding.layoutGecmisContainer.visibility = View.GONE
                    takvimVerileriniYukle()
                    true
                }
                R.id.navigation_gecmis -> {
                    binding.toolbar.title = getString(R.string.title_gecmis)
                    binding.layoutIlaclarContainer.visibility = View.GONE
                    binding.layoutTakvimContainer.visibility = View.GONE
                    binding.layoutGecmisContainer.visibility = View.VISIBLE
                    gecmisFiltreleriniHazirla()
                    gecmisVerileriniYukle()
                    true
                }
                else -> false
            }
        }
    }

    // --- 1. İLAÇLARIM BÖLÜMÜ ---

    private fun ilaclariYereldenYukle() {
        lifecycleScope.launch {
            val yerelListe = repository.getTumIlaclarFromDb()
            ilacAdapter.guncelle(yerelListe)
            binding.progressBar.visibility = View.GONE

            if (yerelListe.isEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
            } else {
                binding.layoutEmpty.visibility = View.GONE
                yerelListe.filter { it.aktifMi }.forEach { ilac ->
                    AlarmScheduler.scheduleAlarm(this@MainActivity, ilac)
                }
            }
        }
    }

    private fun ilaclariSunucudanSenkronizeEt() {
        lifecycleScope.launch {
            val liste = repository.getIlaclar()
            ilacAdapter.guncelle(liste)
            binding.progressBar.visibility = View.GONE

            if (liste.isEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
            } else {
                binding.layoutEmpty.visibility = View.GONE
                liste.filter { it.aktifMi }.forEach { ilac ->
                    AlarmScheduler.scheduleAlarm(this@MainActivity, ilac)
                }
            }
        }
    }

    private fun yeniIlacEkleDialogGoster(hedefCalendar: Calendar? = null) {
        val dialogBinding = DialogAddIlacBinding.inflate(layoutInflater)
        ocrTargetDialogBinding = dialogBinding

        dialogBinding.btnKameraTara.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCameraForOcr()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        dialogBinding.btnGaleriTara.setOnClickListener {
            pickGalleryLauncher.launch("image/*")
        }

        val calendar = Calendar.getInstance().apply { add(Calendar.MINUTE, 2) }
        var secilenSaat = String.format(
            Locale.getDefault(), "%02d:%02d",
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )

        dialogBinding.btnSaatSec.text = "⏰ Saat Seç: $secilenSaat"

        dialogBinding.btnSaatSec.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hourOfDay, minute ->
                    secilenSaat = String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute)
                    dialogBinding.btnSaatSec.text = "⏰ Saat Seç: $secilenSaat"
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        }

        var dialogBaslik = "Yeni İlaç Ekle"
        if (hedefCalendar != null) {
            val hedefFormat = SimpleDateFormat("dd MMMM", Locale.getDefault())
            dialogBaslik = "Yeni İlaç Ekle (${hedefFormat.format(hedefCalendar.time)} İçin)"

            dialogBinding.chipHerGun.isChecked = false
            when (hedefCalendar.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> dialogBinding.chipPzt.isChecked = true
                Calendar.TUESDAY -> dialogBinding.chipSal.isChecked = true
                Calendar.WEDNESDAY -> dialogBinding.chipCar.isChecked = true
                Calendar.THURSDAY -> dialogBinding.chipPer.isChecked = true
                Calendar.FRIDAY -> dialogBinding.chipCum.isChecked = true
                Calendar.SATURDAY -> dialogBinding.chipCmt.isChecked = true
                Calendar.SUNDAY -> dialogBinding.chipPaz.isChecked = true
            }
        }

        // Chip seçimi mantığı
        dialogBinding.chipHerGun.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                dialogBinding.chipPzt.isChecked = false
                dialogBinding.chipSal.isChecked = false
                dialogBinding.chipCar.isChecked = false
                dialogBinding.chipPer.isChecked = false
                dialogBinding.chipCum.isChecked = false
                dialogBinding.chipCmt.isChecked = false
                dialogBinding.chipPaz.isChecked = false
            }
        }

        val gunChips = listOf(
            dialogBinding.chipPzt,
            dialogBinding.chipSal,
            dialogBinding.chipCar,
            dialogBinding.chipPer,
            dialogBinding.chipCum,
            dialogBinding.chipCmt,
            dialogBinding.chipPaz
        )

        gunChips.forEach { chip ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    dialogBinding.chipHerGun.isChecked = false
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(dialogBaslik)
            .setView(dialogBinding.root)
            .setPositiveButton("İlacı Kaydet & Alarmı Kur") { dialog, _ ->
                val ilacAdi = dialogBinding.etIlacAdi.text.toString().trim()
                val dozaj = dialogBinding.etDozaj.text.toString().trim()

                if (ilacAdi.isEmpty()) {
                    Toast.makeText(this, "Lütfen ilaç adını girin.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val secilenGunler = mutableListOf<String>()
                if (dialogBinding.chipHerGun.isChecked) {
                    secilenGunler.add("Her Gün")
                } else {
                    if (dialogBinding.chipPzt.isChecked) secilenGunler.add("Pzt")
                    if (dialogBinding.chipSal.isChecked) secilenGunler.add("Sal")
                    if (dialogBinding.chipCar.isChecked) secilenGunler.add("Çar")
                    if (dialogBinding.chipPer.isChecked) secilenGunler.add("Per")
                    if (dialogBinding.chipCum.isChecked) secilenGunler.add("Cum")
                    if (dialogBinding.chipCmt.isChecked) secilenGunler.add("Cmt")
                    if (dialogBinding.chipPaz.isChecked) secilenGunler.add("Paz")
                }

                if (secilenGunler.isEmpty()) {
                    secilenGunler.add("Her Gün")
                }

                val yeniIlac = Ilac(
                    ilacAdi = ilacAdi,
                    dozaj = if (dozaj.isEmpty()) "1 Tablet" else dozaj,
                    saat = secilenSaat,
                    gunler = secilenGunler,
                    aktifMi = true
                )

                ilacEkle(yeniIlac)
                dialog.dismiss()
            }
            .setNegativeButton("İptal") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun ilacEkle(ilac: Ilac) {
        lifecycleScope.launch {
            val eklenenIlac = repository.addIlac(ilac)
            AlarmScheduler.scheduleAlarm(this@MainActivity, eklenenIlac)
            Toast.makeText(
                this@MainActivity,
                "İlaç kaydedildi ve alarm kuruldu! ⏰ (${eklenenIlac.saat})",
                Toast.LENGTH_LONG
            ).show()
            ilaclariYereldenYukle()
        }
    }

    private fun durumDegistir(ilac: Ilac) {
        lifecycleScope.launch {
            val guncel = repository.toggleDurum(ilac)
            if (guncel.aktifMi) {
                AlarmScheduler.scheduleAlarm(this@MainActivity, guncel)
                Toast.makeText(this@MainActivity, "Alarm aktifleşti.", Toast.LENGTH_SHORT).show()
            } else {
                AlarmScheduler.cancelAlarm(this@MainActivity, guncel.id)
                Toast.makeText(this@MainActivity, "Alarm kapatıldı.", Toast.LENGTH_SHORT).show()
            }
            ilaclariYereldenYukle()
        }
    }

    private fun ilacSilOnayGoster(ilac: Ilac) {
        MaterialAlertDialogBuilder(this)
            .setTitle("İlacı Sil")
            .setMessage("${ilac.ilacAdi} ilacını silmek istediğinize emin misiniz?")
            .setPositiveButton("Sil") { _, _ -> ilacSil(ilac) }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun ilacSil(ilac: Ilac) {
        lifecycleScope.launch {
            repository.deleteIlac(ilac.id)
            AlarmScheduler.cancelAlarm(this@MainActivity, ilac.id)
            Toast.makeText(this@MainActivity, "İlaç silindi.", Toast.LENGTH_SHORT).show()
            ilaclariYereldenYukle()
        }
    }

    // --- 2. İLAÇ TAKVİMİ BÖLÜMÜ ---

    private fun setupTakvimControls() {
        binding.btnOncekiAy.setOnClickListener {
            secilenTakvimCalendar.add(Calendar.MONTH, -1)
            takvimVerileriniYukle()
        }

        binding.btnSonrakiAy.setOnClickListener {
            secilenTakvimCalendar.add(Calendar.MONTH, 1)
            takvimVerileriniYukle()
        }

        binding.btnBugun.setOnClickListener {
            secilenTakvimCalendar = Calendar.getInstance()
            takvimVerileriniYukle()
        }

        binding.cardGunlukOzet.setOnClickListener {
            aylikGecmisDialogGoster()
        }

        binding.btnBuTariheIlacEkle.setOnClickListener {
            yeniIlacEkleDialogGoster(secilenTakvimCalendar)
        }
    }

    private fun takvimVerileriniYukle() {
        lifecycleScope.launch {
            val tarihStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(secilenTakvimCalendar.time)
            val baslikFormat = SimpleDateFormat("dd MMMM yyyy, EEEE", Locale.getDefault())
            val baslikTarihMetin = baslikFormat.format(secilenTakvimCalendar.time)

            binding.tvTakvimBaslik.text = getString(R.string.header_planned_doses, baslikTarihMetin)

            val dozlar = repository.getDozlarForDate(tarihStr)

            takvimAdapter.guncelle(dozlar)

            val simdiTarihStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val isGecmisTarih = tarihStr < simdiTarihStr
            val isGelecekTarih = tarihStr > simdiTarihStr

            val alinanSayisi = dozlar.count { it.durum == DozKaydiEntity.DURUM_ALINDI }
            val atlananSayisi = dozlar.count { it.durum == DozKaydiEntity.DURUM_ATLANDI || it.durum == DozKaydiEntity.DURUM_SURESI_GECTI || (isGecmisTarih && it.durum == DozKaydiEntity.DURUM_BEKLIYOR) }
            val bekleyenSayisi = dozlar.count { !isGecmisTarih && (it.durum == DozKaydiEntity.DURUM_BEKLIYOR || it.durum == DozKaydiEntity.DURUM_ERTELENDI) }
            val toplamDoz = dozlar.size

            binding.tvOzetAlinan.text = getString(R.string.summary_taken, alinanSayisi)
            binding.tvOzetAtlanan.text = getString(R.string.summary_skipped, atlananSayisi)
            binding.tvOzetBekleyen.text = if (isGelecekTarih) getString(R.string.summary_scheduled, bekleyenSayisi) else getString(R.string.summary_pending, bekleyenSayisi)

            if (toplamDoz > 0) {
                val oran = (alinanSayisi * 100) / toplamDoz
                binding.tvOzetDurumRozet.text = getString(R.string.summary_complete, oran)
                if (oran == 100) {
                    binding.tvOzetDurumRozet.setBackgroundColor(Color.parseColor("#E8F5E9"))
                    binding.tvOzetDurumRozet.setTextColor(Color.parseColor("#2E7D32"))
                } else if (atlananSayisi > 0) {
                    binding.tvOzetDurumRozet.setBackgroundColor(Color.parseColor("#FFEBEE"))
                    binding.tvOzetDurumRozet.setTextColor(Color.parseColor("#C62828"))
                } else {
                    binding.tvOzetDurumRozet.setBackgroundColor(Color.parseColor("#FFF3E0"))
                    binding.tvOzetDurumRozet.setTextColor(Color.parseColor("#EF6C00"))
                }
            } else {
                binding.tvOzetDurumRozet.text = getString(R.string.summary_no_doses)
                binding.tvOzetDurumRozet.setBackgroundColor(Color.parseColor("#F5F5F5"))
                binding.tvOzetDurumRozet.setTextColor(Color.parseColor("#757575"))
            }

            val tarihKisaMetin = SimpleDateFormat("dd MMMM", Locale.getDefault()).format(secilenTakvimCalendar.time)
            if (dozlar.isEmpty()) {
                binding.layoutTakvimBosContainer.visibility = View.VISIBLE
                binding.tvTakvimBos.text = getString(R.string.empty_takvim, tarihKisaMetin)
                binding.btnBuTariheIlacEkle.text = getString(R.string.btn_add_to_date, tarihKisaMetin)
            } else {
                binding.layoutTakvimBosContainer.visibility = View.GONE
            }

            aylikTakvimGridYukle()
        }
    }

    private fun aylikTakvimGridYukle() {
        lifecycleScope.launch {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val bugunStr = sdf.format(Date())

            val ayCal = secilenTakvimCalendar.clone() as Calendar
            ayCal.set(Calendar.DAY_OF_MONTH, 1)

            val secilenTarihStr = sdf.format(secilenTakvimCalendar.time)

            val ayYilFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            val ayYilMetin = ayYilFormat.format(ayCal.time).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            binding.tvAyYilBaslik.text = "📅 $ayYilMetin"

            var dayOfWeek = ayCal.get(Calendar.DAY_OF_WEEK) - 1
            if (dayOfWeek == 0) dayOfWeek = 7

            val baslangicCal = ayCal.clone() as Calendar
            baslangicCal.add(Calendar.DAY_OF_MONTH, -(dayOfWeek - 1))

            val bitisCal = baslangicCal.clone() as Calendar
            bitisCal.add(Calendar.DAY_OF_MONTH, 42)

            val baslangicStr = sdf.format(baslangicCal.time)
            val bitisStr = sdf.format(bitisCal.time)

            val durumMap = repository.getAylikDozDurumMap(baslangicStr, bitisStr)

            val gunlerListesi = mutableListOf<TakvimGunModel>()
            val tempCal = baslangicCal.clone() as Calendar
            val hedefAy = ayCal.get(Calendar.MONTH)

            for (i in 0 until 42) {
                val tStr = sdf.format(tempCal.time)
                val gunSayisi = tempCal.get(Calendar.DAY_OF_MONTH).toString()
                val buAydaMi = tempCal.get(Calendar.MONTH) == hedefAy
                val bugunMu = tStr == bugunStr
                val seciliMi = tStr == secilenTarihStr
                val durum = durumMap[tStr] ?: "BOS"

                gunlerListesi.add(
                    TakvimGunModel(
                        tarihStr = tStr,
                        gunSayisi = gunSayisi,
                        buAydaMi = buAydaMi,
                        bugunMu = bugunMu,
                        seciliMi = seciliMi,
                        durum = durum
                    )
                )
                tempCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            takvimGridAdapter.guncelle(gunlerListesi)
        }
    }

    private fun aylikGecmisDialogGoster() {
        lifecycleScope.launch {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val baslangicCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }
            val bitisCal = Calendar.getInstance()

            val baslangicStr = sdf.format(baslangicCal.time)
            val bitisStr = sdf.format(bitisCal.time)

            val tumDozlar = repository.getDozGecmisi(null, baslangicStr, bitisStr)
            val gunlukGruplar = tumDozlar.groupBy { it.planlananTarih }

            val mesajBuilder = StringBuilder()
            val formatTarih = SimpleDateFormat("dd MMMM, EEEE", Locale.getDefault())

            gunlukGruplar.keys.sortedDescending().take(15).forEach { tarih ->
                val dozlar = gunlukGruplar[tarih] ?: emptyList()
                val alinan = dozlar.count { it.durum == DozKaydiEntity.DURUM_ALINDI }
                val atlanan = dozlar.count { it.durum == DozKaydiEntity.DURUM_ATLANDI || it.durum == DozKaydiEntity.DURUM_SURESI_GECTI }
                val bekleyen = dozlar.count { it.durum == DozKaydiEntity.DURUM_BEKLIYOR || it.durum == DozKaydiEntity.DURUM_ERTELENDI }

                val tarihObj = try { sdf.parse(tarih) } catch (_: Exception) { null }
                val tarihBaslik = if (tarihObj != null) formatTarih.format(tarihObj) else tarih

                val simge = when {
                    alinan == dozlar.size && dozlar.isNotEmpty() -> "🟢 [Tam Alındı]"
                    atlanan > 0 -> "🔴 [Atlanan/Yanıtsız Var]"
                    else -> "🟡 [Bekleyen Var]"
                }

                mesajBuilder.append("$simge $tarihBaslik\n")
                mesajBuilder.append("   • Alınan: $alinan | Atlanan: $atlanan | Bekleyen: $bekleyen\n\n")
            }

            if (mesajBuilder.isEmpty()) {
                mesajBuilder.append("Son 30 güne ait henüz doz kaydı oluşmadı.")
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("🗓️ Son 30 Günün Doz Haritası")
                .setMessage(mesajBuilder.toString())
                .setPositiveButton("Tamam", null)
                .show()
        }
    }

    private fun dozuOnayla(doz: DozKaydiEntity, yeniDurum: String) {
        lifecycleScope.launch {
            repository.updateDozDurumu(doz.id, yeniDurum)
            val mesaj = if (yeniDurum == DozKaydiEntity.DURUM_ALINDI) getString(R.string.toast_take_success, doz.ilacAdi) else getString(R.string.toast_skip_success, doz.ilacAdi)
            Toast.makeText(this@MainActivity, mesaj, Toast.LENGTH_SHORT).show()
            takvimVerileriniYukle()
        }
    }

    private fun dozuErteleDialogGoster(doz: DozKaydiEntity) {
        val secenekler = arrayOf("10 Dakika Ertele", "15 Dakika Ertele", "30 Dakika Ertele", "1 Saat Ertele")
        val dakikalar = intArrayOf(10, 15, 30, 60)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.title_snooze, doz.ilacAdi))
            .setItems(secenekler) { _, which ->
                val erteleDk = dakikalar[which]
                val triggerCal = Calendar.getInstance().apply { add(Calendar.MINUTE, erteleDk) }
                val yeniSaatFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).format(triggerCal.time)

                lifecycleScope.launch {
                    repository.updateDozDurumu(doz.id, DozKaydiEntity.DURUM_ERTELENDI, yeniSaatFormat)
                    AlarmScheduler.scheduleSnoozeAlarm(this@MainActivity, doz.ilacId, doz.ilacAdi, doz.dozaj, erteleDk)
                    takvimVerileriniYukle()
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // --- 3. KULLANIM GEÇMİŞİ BÖLÜMÜ ---

    private fun setupGecmisControls() {
        binding.toggleGecmisPeriyot.check(R.id.btnGecmisBugun)

        binding.toggleGecmisPeriyot.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                secilenGecmisPeriyot = when (checkedId) {
                    R.id.btnGecmisBugun -> "BUGUN"
                    R.id.btnGecmisHafta -> "HAFTA"
                    R.id.btnGecmisTum -> "TUM"
                    else -> "BUGUN"
                }
                gecmisVerileriniYukle()
            }
        }
    }

    private fun gecmisFiltreleriniHazirla() {
        lifecycleScope.launch {
            val ilaclar = repository.getTumIlaclarFromDb()
            val tumIlaclarMetin = getString(R.string.all_medicines)
            val filtreListesi = mutableListOf(tumIlaclarMetin)
            filtreListesi.addAll(ilaclar.map { it.ilacAdi }.distinct())

            val spinnerAdapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                filtreListesi
            )
            binding.spinnerIlacFiltre.adapter = spinnerAdapter

            binding.spinnerIlacFiltre.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    secilenGecmisIlacFiltre = filtreListesi[position]
                    gecmisVerileriniYukle()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun gecmisVerileriniYukle() {
        lifecycleScope.launch {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val bugun = Calendar.getInstance()
            val bitisTarih = sdf.format(bugun.time)

            val baslangicCal = Calendar.getInstance()
            when (secilenGecmisPeriyot) {
                "BUGUN" -> { /* baslangicCal is bugun */ }
                "HAFTA" -> { baslangicCal.add(Calendar.DAY_OF_YEAR, -7) }
                "TUM" -> { baslangicCal.add(Calendar.DAY_OF_YEAR, -365) }
            }
            val baslangicTarih = sdf.format(baslangicCal.time)

            val gecmisDozlar = repository.getDozGecmisi(secilenGecmisIlacFiltre, baslangicTarih, bitisTarih)
            gecmisAdapter.guncelle(gecmisDozlar)

            if (gecmisDozlar.isEmpty()) {
                binding.tvGecmisBos.visibility = View.VISIBLE
            } else {
                binding.tvGecmisBos.visibility = View.GONE
            }
        }
    }
}
