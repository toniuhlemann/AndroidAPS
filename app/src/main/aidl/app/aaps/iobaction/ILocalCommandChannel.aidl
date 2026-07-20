// LocalCommandChannel v1 (Spec v1.2/v1.3, Codex R3 OFF/Auth-GO):
// EINE synchrone Methode; Request/Antwort sind strikt gehaertete Bundles mit
// ausschliesslich primitiven String-Feldern (payloadJsonUtf8, hmacHex / ACK-Felder).
package app.aaps.iobaction;

interface ILocalCommandChannel {
    Bundle execute(in Bundle request);
}
