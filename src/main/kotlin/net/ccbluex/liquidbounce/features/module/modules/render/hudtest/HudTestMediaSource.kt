/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.render.hudtest

/**
 * Source of the Windows media bridge helper, kept here rather than as a resource so the module is
 * self-contained: [HudTestMediaCompiler] writes it into the client's config folder and compiles it
 * with the .NET Framework compiler that ships with Windows.
 *
 * It prints one unit-separator delimited line per poll and accepts `next`, `prev` and `toggle` to
 * drive the session. The cover art is the reason this exists at all - PowerShell 5.1 cannot
 * marshal the WinRT stream the thumbnail comes back as.
 */
internal object HudTestMediaSource {

    /** The verbatim C# program. Changing it re-triggers a compile on the next start. */
    val CODE: String = """
using System;
using System.IO;
using Windows.Media.Control;
using Windows.Storage.Streams;

public static class Smtc {
    static GlobalSystemMediaTransportControlsSession Session() {
        var mgr = GlobalSystemMediaTransportControlsSessionManager.RequestAsync().AsTask().GetAwaiter().GetResult();
        return mgr.GetCurrentSession();
    }
    public static int Main(string[] args) {
        var s = Session();
        if (s == null) { Console.Out.WriteLine("none"); return 0; }
        if (args.Length > 0 && args[0] != "poll") {
            if (args[0] == "next") s.TrySkipNextAsync().AsTask().GetAwaiter().GetResult();
            else if (args[0] == "prev") s.TrySkipPreviousAsync().AsTask().GetAwaiter().GetResult();
            else if (args[0] == "toggle") s.TryTogglePlayPauseAsync().AsTask().GetAwaiter().GetResult();
            Console.Out.WriteLine("ok");
            return 0;
        }
        var p = s.TryGetMediaPropertiesAsync().AsTask().GetAwaiter().GetResult();
        var pb = s.GetPlaybackInfo();
        var tl = s.GetTimelineProperties();
        string thumb = "";
        if (p.Thumbnail != null) {
            try {
                using (var st = p.Thumbnail.OpenReadAsync().AsTask().GetAwaiter().GetResult())
                using (var net = System.IO.WindowsRuntimeStreamExtensions.AsStreamForRead(st))
                using (var mem = new MemoryStream()) { net.CopyTo(mem); thumb = Convert.ToBase64String(mem.ToArray()); }
            } catch { }
        }
        var o = new StreamWriter(Console.OpenStandardOutput(), new System.Text.UTF8Encoding(false));
        o.WriteLine(string.Join("\u001f", new string[] { "ok", p.Title ?? "", p.Artist ?? "",
            pb.PlaybackStatus.ToString(), ((long)tl.Position.TotalMilliseconds).ToString(),
            ((long)(tl.EndTime - tl.StartTime).TotalMilliseconds).ToString(), thumb }));
        o.Flush();
        return 0;
    }
}
""".trimIndent()
}
