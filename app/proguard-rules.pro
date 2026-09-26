# R8-Regeln für den Release-Build (zusätzlich zu proguard-android-optimize.txt).

# OsmAnd-AIDL-API: OsmAnd schickt Parcelables über Binder mit ihrem
# vollqualifizierten Klassennamen (z.B. net.osmand.aidlapi.navigation.
# ADirectionInfo), ausgepackt wird per Class.forName + CREATOR. Umbenennen
# oder Entfernen -- auch von Klassen, die unser Code nie direkt anfasst --
# bricht die Anbindung zur Laufzeit. Deshalb komplett behalten.
-keep class net.osmand.aidlapi.** { *; }

# Open Source: nur verkleinern, nicht verschleiern -- Stacktraces aus
# Fehlerberichten bleiben so direkt lesbar.
-dontobfuscate
