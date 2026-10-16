package org.koitharu.kotatsu.list.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.databinding.ItemRecommendationWarningBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.RecommendationWarning
import org.koitharu.kotatsu.suggestions.local.showRecommendationErrorDetails

fun recommendationWarningAD() =
    adapterDelegateViewBinding<RecommendationWarning, ListModel, ItemRecommendationWarningBinding>(
        { inflater, parent -> ItemRecommendationWarningBinding.inflate(inflater, parent, false) },
    ) {
        binding.buttonDetails.setOnClickListener { showRecommendationErrorDetails(context, item.details) }
    }
