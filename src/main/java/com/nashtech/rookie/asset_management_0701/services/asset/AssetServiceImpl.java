package com.nashtech.rookie.asset_management_0701.services.asset;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nashtech.rookie.asset_management_0701.dtos.filters.AssetFilter;
import com.nashtech.rookie.asset_management_0701.dtos.requests.asset.AssetCreateDto;
import com.nashtech.rookie.asset_management_0701.dtos.responses.PaginationResponse;
import com.nashtech.rookie.asset_management_0701.dtos.responses.asset.AssetResponseDto;
import com.nashtech.rookie.asset_management_0701.entities.Asset;
import com.nashtech.rookie.asset_management_0701.entities.Category;
import com.nashtech.rookie.asset_management_0701.entities.Location;
import com.nashtech.rookie.asset_management_0701.enums.EAssetState;
import com.nashtech.rookie.asset_management_0701.exceptions.AppException;
import com.nashtech.rookie.asset_management_0701.exceptions.ErrorCode;
import com.nashtech.rookie.asset_management_0701.mappers.AssetMapper;
import com.nashtech.rookie.asset_management_0701.repositories.AssetRepository;
import com.nashtech.rookie.asset_management_0701.repositories.AssignmentRepository;
import com.nashtech.rookie.asset_management_0701.repositories.CategoryRepository;
import com.nashtech.rookie.asset_management_0701.utils.PageSortUtil;
import com.nashtech.rookie.asset_management_0701.utils.asset_utils.AssetUtil;
import com.nashtech.rookie.asset_management_0701.utils.auth_util.AuthUtil;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssetServiceImpl implements AssetService {

    private final AssetRepository assetRepository;
    private final AssetMapper assetMapper;
    private final CategoryRepository categoryRepository;
    private final AssignmentRepository assignmentRepository;
    private final AuthUtil authUtil;

    @Override
    @Transactional
    public AssetResponseDto createAsset (AssetCreateDto assetCreateDto) {
        var category = categoryRepository
                .findByName(assetCreateDto.getCategory())
                .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));

        validateInstallDate(assetCreateDto);
        Asset asset = assetMapper.toAsset(assetCreateDto);
        asset.setCategory(category);
        asset.setLocation(authUtil.getCurrentUser().getLocation());

        Long count = assetRepository.countByAssetCodeStartingWith(category.getCode()) + 1;
        asset.setAssetCode(AssetUtil.generateAssetCode(count, category.getCode()));

        asset = assetRepository.save(asset);
        return assetMapper.toAssetResponseDto(asset);
    }

    private void validateInstallDate (AssetCreateDto assetCreateDto) {
        LocalDate installDate = assetCreateDto.getInstallDate();
        LocalDate toThreeMonthsAgo = LocalDate.now().minusMonths(3);

        if (installDate.isBefore(toThreeMonthsAgo)) {
            throw new AppException(ErrorCode.ASSET_INSTALLED_DATE_TOO_OLD);
        }
    }

    @Override
    public PaginationResponse<AssetResponseDto> getAllAssets (AssetFilter assetFilter) {

        Sort sort = Sort.by(PageSortUtil.parseSortDirection(assetFilter.getSortDir()), assetFilter.getOrderBy());
        Pageable pageable = PageSortUtil.createPageRequest(assetFilter.getPageNumber()
                , assetFilter.getPageSize(), sort);
        Location currentLocation = authUtil.getCurrentUser().getLocation();


        // check if the categoryIds are valid
        List<Category> categories = categoryRepository.findAllById(assetFilter.getCategoryIds());
        if (categories.size() != assetFilter.getCategoryIds().size()) {
            throw new AppException(ErrorCode.CATEGORY_NOT_FOUND);
        }

        Page<Asset> assets = assetRepository.findAll(
                Specification.where(AssetSpecification.hasAssetName(assetFilter.getSearchString()))
                        .or(AssetSpecification.hasAssetCode(assetFilter.getSearchString()))
                        .and(AssetSpecification.hasStates(assetFilter.getStates()))
                        .and(AssetSpecification.hasLocation(currentLocation))
                        .and(AssetSpecification.hasCategories(categories)),
                pageable);

        return PaginationResponse.<AssetResponseDto>builder()
                .page(pageable.getPageNumber() + 1)
                .total(assets.getTotalElements())
                .itemsPerPage(pageable.getPageSize())
                .data(assets.map(assetMapper::toAssetResponseDto).toList())
                .build();
    }

    @Override
    public AssetResponseDto getAssetById (Long id) {
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ASSET_NOT_FOUND));
        return assetMapper.toAssetResponseDto(asset);
    }

    @Override
    @Transactional
    public void deleteAsset (Long id) {
        if (assignmentRepository.existsByAssetId(id)) {
            throw new AppException(ErrorCode.ASSET_WAS_ASSIGNED);
        }
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ASSET_NOT_FOUND));
        if (asset.getState().equals(EAssetState.ASSIGNED)) {
            throw new AppException(ErrorCode.ASSET_IS_ASSIGNED);
        }
        assetRepository.delete(asset);
    }
}
