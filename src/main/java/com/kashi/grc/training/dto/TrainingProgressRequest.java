package com.kashi.grc.training.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /v1/training/assignments/{id}/progress — the player heartbeat, roughly
 * every ten seconds.
 *
 * positionSeconds is where the playhead is. playedSeconds is how much actually
 * played since the last heartbeat, as measured by the player.
 *
 * Neither is trusted. The service clamps playedSeconds to the wall-clock time
 * elapsed since the previous heartbeat, so a client claiming 600 seconds of
 * playback ten seconds after its last call gets ten.
 */
@Getter @Setter
public class TrainingProgressRequest {

    @NotNull(message = "itemId is required")
    private Long itemId;

    @NotNull @Min(0)
    private Integer positionSeconds;

    @Min(0)
    private Integer playedSeconds;
}
