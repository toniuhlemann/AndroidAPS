// LocalCommandChannel v1 (fork, Spec v1.2/v1.3):
// ONE synchronous method; request and answer are strictly hardened Bundles with
// primitive fields only (payloadJsonUtf8, hmacHex / ACK fields).
package app.aaps.iobaction;

interface ILocalCommandChannel {
    Bundle execute(in Bundle request);
}
