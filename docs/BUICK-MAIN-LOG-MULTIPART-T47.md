# Buick-main T47: multi-batch confirmed diagnostic uploads

Date: 2026-10-23; scope Buick-main only.

The phone can now preview and explicitly confirm up to 256 redacted lines per collection (formerly 64).
One user action queues the exact bounded preview. It streams in <=8-line Gateway logs_v1 packets,
each with independent UUID and server acknowledgement, preserving compatibility with the
unchanged gateway's max-8-lines-per-message validator. Status shows acknowledged/total lines.
The Gateway server is not modified. A failed transport closes the queue and must NOT resume
previously approved system logs after reconnect without another explicit preview/confirmation.
No background system-log capture or unconditional upload is added.

Capture limits: 300 previous-boot crash lines; DropBox shell output at most 96k bytes per tag;
maximum 256 uploaded lines, each <=256 characters after existing redaction.
The system_server_crash section gets priority so more exception context and stack frames survive.
The normal warning preview is also bounded to 256 lines.
