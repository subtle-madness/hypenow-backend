package com.celfit.was.saved;

import java.util.List;

public record SavedInfluencersResponse(List<SavedInfluencer> items) {

	public static SavedInfluencersResponse of(List<SavedInfluencer> items) {
		return new SavedInfluencersResponse(items);
	}
}
