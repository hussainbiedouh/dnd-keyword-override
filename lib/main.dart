import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DndOverrideApp());
}

class DndOverrideApp extends StatelessWidget {
  const DndOverrideApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'DnD Override',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  static const _channel = MethodChannel('dnd_override/channel');

  List<String> _keywords = [];
  bool _notifAccess = false;
  bool _serviceConnected = false;
  bool _batteryIgnored = false;
  final TextEditingController _input = TextEditingController();
  final TextEditingController _testInput = TextEditingController();
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _channel.invokeMethod('requestNotificationPermission');
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _input.dispose();
    _testInput.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refresh();
    }
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final errs = <String>[];
    try {
      final kws = await _channel.invokeMethod<List<dynamic>>('getKeywords');
      setState(() => _keywords = (kws ?? []).map((e) => e.toString()).toList());
    } catch (e) {
      errs.add('keywords: $e');
    }
    // Retry a few times for notification access / service connection, since
    // on Xiaomi HyperOS the service may take a moment to bind after granting.
    bool na = false;
    bool sc = false;
    for (int i = 0; i < 3; i++) {
      try {
        na = await _channel.invokeMethod<bool>('isNotificationAccessGranted') ?? false;
        sc = await _channel.invokeMethod<bool>('isServiceConnected') ?? false;
        if (na || sc) break;
      } catch (e) {
        errs.add('notifAccess: $e');
      }
      if (i < 2) await Future.delayed(const Duration(milliseconds: 500));
    }
    setState(() {
      _notifAccess = na;
      _serviceConnected = sc;
    });

    try {
      final bi = await _channel.invokeMethod<bool>('isIgnoringBatteryOptimizations');
      setState(() => _batteryIgnored = bi ?? false);
    } catch (e) {
      errs.add('battery: $e');
    }
    setState(() {
      _loading = false;
      _error = errs.isEmpty ? null : errs.join('; ');
    });
  }

  Future<void> _add() async {
    final v = _input.text.trim();
    if (v.isEmpty) return;
    try {
      await _channel.invokeMethod('addKeyword', {'keyword': v});
      _input.clear();
      await _refresh();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Add failed: $e')));
      }
    }
  }

  Future<void> _remove(String kw) async {
    try {
      await _channel.invokeMethod('removeKeyword', {'keyword': kw});
      await _refresh();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Remove failed: $e')));
      }
    }
  }

  Future<void> _openNotifSettings() async {
    await _channel.invokeMethod('openNotificationAccessSettings');
  }

  Future<void> _requestBattery() async {
    await _channel.invokeMethod('requestIgnoreBatteryOptimizations');
    await Future.delayed(const Duration(seconds: 1));
    await _refresh();
  }

  Future<void> _sendTestNotification({bool ringtone = false}) async {
    await _channel.invokeMethod('requestNotificationPermission');
    final kw = _testInput.text.trim().isEmpty
        ? (_keywords.isNotEmpty ? _keywords.first : 'test')
        : _testInput.text.trim();
    try {
      final ok = await _channel.invokeMethod<bool>('sendTestNotification', {'keyword': kw, 'ringtone': ringtone});
      if (!mounted) return;
      // Give the service a moment to process the notification.
      await Future.delayed(const Duration(seconds: 1));
      String debug = '';
      try {
        debug = await _channel.invokeMethod<String>('getLastDebugInfo') ?? '';
      } catch (_) {}
      if (!mounted) return;
      final msg = !(_notifAccess && _serviceConnected)
          ? 'Notification access not active. Enable it first (see Setup).'
          : (ok != true
              ? 'Failed to post notification.'
              : 'Posted "$kw" (${ringtone ? "ringtone" : "notification"} sound). Service: $debug');
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg), duration: const Duration(seconds: 6)));
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Error: $e')));
      }
    }
  }



  @override
  Widget build(BuildContext context) {
    if (_loading && _keywords.isEmpty && !_notifAccess) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return Scaffold(
      appBar: AppBar(
        title: const Text('DnD Keyword Override'),
        actions: [
          IconButton(icon: const Icon(Icons.refresh), onPressed: _refresh),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          if (_error != null)
            Card(
              color: Colors.red.shade50,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Text(_error!, style: const TextStyle(color: Colors.red, fontSize: 12)),
              ),
            ),
          _statusCard(),
          const SizedBox(height: 12),
          _keywordsCard(),
          const SizedBox(height: 12),
          _testCard(),
          const SizedBox(height: 12),
          _setupCard(),
          const SizedBox(height: 12),
          _infoCard(),
        ],
      ),
    );
  }

  Widget _statusCard() {
    final ok = _notifAccess || _serviceConnected;
    return Card(
      child: ListTile(
        leading: Icon(ok ? Icons.check_circle : Icons.error,
            color: ok ? Colors.green : Colors.red),
        title: Text(ok
            ? 'Notification access: GRANTED'
            : 'Notification access: NOT GRANTED'),
        subtitle: Text(ok
            ? (_serviceConnected
                ? 'Service connected. Monitoring notifications in DnD.'
                : 'Access granted. Service binding... tap Refresh.')
            : 'Grant notification access to start monitoring.'),
        trailing: ok
            ? (_serviceConnected
                ? null
                : TextButton(onPressed: _refresh, child: const Text('Refresh')))
            : TextButton(onPressed: _openNotifSettings, child: const Text('Grant')),
      ),
    );
  }

  Widget _keywordsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Keywords', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _input,
                    decoration: const InputDecoration(
                      hintText: 'Enter keyword (e.g. Mom, urgent)',
                      border: OutlineInputBorder(),
                    ),
                    onSubmitted: (_) => _add(),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton(onPressed: _add, child: const Text('Add')),
              ],
            ),
            const SizedBox(height: 12),
            if (_keywords.isEmpty)
              const Text('No keywords yet. Add at least one.',
                  style: TextStyle(color: Colors.grey))
            else
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: _keywords
                    .map((k) => Chip(
                          label: Text(k),
                          onDeleted: () => _remove(k),
                        ))
                    .toList(),
              ),
          ],
        ),
      ),
    );
  }

  Widget _testCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Test override', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 8),
            TextField(
              controller: _testInput,
              decoration: const InputDecoration(
                hintText: 'Custom test word (optional, uses first keyword if empty)',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: () => _sendTestNotification(),
                    icon: const Icon(Icons.notifications_active),
                    label: const Text('Send test notification'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () => _sendTestNotification(ringtone: true),
                    icon: const Icon(Icons.ring_volume),
                    label: const Text('Ringtone test'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _setupCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Setup (Xiaomi HyperOS)', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
            const SizedBox(height: 8),
            _setupRow('Notification access', _notifAccess, _openNotifSettings),
            _setupRow('Battery optimization ignored', _batteryIgnored, _requestBattery),
            const SizedBox(height: 8),
            const Text(
              'Also enable Auto-start and disable battery restrictions for this app in HyperOS Security / Battery settings, '
              'otherwise the service may be killed in background.',
              style: TextStyle(fontSize: 12, color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }

  Widget _setupRow(String label, bool ok, VoidCallback action) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(ok ? Icons.check_circle : Icons.radio_button_unchecked,
          color: ok ? Colors.green : Colors.orange),
      title: Text(label),
      trailing: ok ? null : TextButton(onPressed: action, child: const Text('Fix')),
    );
  }

  Widget _infoCard() {
    return const Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Text(
          'When Do Not Disturb is active, this app monitors incoming notifications. '
          'If a notification title or body contains any of your keywords, it overrides DnD '
          'and plays the default notification sound, vibrates, and wakes the screen.\n\n'
          'Matching is case-insensitive and uses "contains". '
          'DnD policy access is not required (override plays sound directly).',
          style: TextStyle(fontSize: 13),
        ),
      ),
    );
  }
}
