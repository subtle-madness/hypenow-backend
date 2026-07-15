package com.celfit.was.saved;

import java.util.List;

public record SavedContentsResponse(List<SavedContent> items) {

	public static SavedContentsResponse of(List<SavedContent> items) {
		return new SavedContentsResponse(items);
	}
}
